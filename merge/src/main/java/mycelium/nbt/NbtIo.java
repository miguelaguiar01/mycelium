package mycelium.nbt;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UTFDataFormatException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Reads and writes binary NBT: big-endian, with strings in Java's modified UTF-8.
 *
 * <p>Every tag is kept, known or not. Reading and then writing a tag gives back the same bytes,
 * because compounds keep their key order and lists keep their declared element type.
 */
public final class NbtIo {

    /** The nesting limit the game enforces. Deeper input is rejected instead of overflowing the stack. */
    public static final int MAX_DEPTH = 512;

    /**
     * The most tags one tree may hold. A chunk of the game holds a few hundred thousand at most; the
     * limit keeps a small crafted file from unfolding into gigabytes of objects.
     */
    public static final int MAX_TAGS = 8_000_000;

    /** A standalone file larger than this once decompressed is refused instead of exhausting memory. */
    public static final int MAX_FILE_BYTES = 1 << 28;

    /** Order in which compound keys are written. */
    public enum KeyOrder {
        /** The order the compound holds them in: for an unchanged tag, the order it was read in. */
        STORED,
        /** Sorted by key, so equal tags give equal bytes. */
        SORTED
    }

    private NbtIo() {
    }

    /** Parses one named root tag. Bytes after the root tag are ignored, as the game ignores them. */
    public static NamedTag read(byte[] data) throws NbtFormatException {
        return read(data, 0, data.length);
    }

    public static NamedTag read(byte[] data, int offset, int length) throws NbtFormatException {
        try {
            return new Reader(data, offset, length).readRoot();
        } catch (NbtFormatException e) {
            throw e;
        } catch (BufferUnderflowException | EOFException e) {
            throw new NbtFormatException("NBT data ends early", e);
        } catch (UTFDataFormatException e) {
            throw new NbtFormatException("malformed string in NBT data", e);
        } catch (IOException e) {
            throw new NbtFormatException("unreadable NBT data: " + e.getMessage(), e);
        }
    }

    public static byte[] write(NamedTag root) {
        return write(root, KeyOrder.STORED);
    }

    public static byte[] write(NamedTag root, KeyOrder keyOrder) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(8192);
        try {
            write(root, keyOrder, bytes);
        } catch (IOException e) {
            // A byte array never fails; the only cause is a string too long for NBT.
            throw new IllegalArgumentException("tag cannot be written as NBT: " + e.getMessage(), e);
        }
        return bytes.toByteArray();
    }

    public static void write(NamedTag root, KeyOrder keyOrder, OutputStream out) throws IOException {
        DataOutputStream data = new DataOutputStream(out);
        data.writeByte(root.tag().type().id());
        data.writeUTF(root.name());
        writePayload(root.tag(), keyOrder, data);
        data.flush();
    }

    /** Reads a standalone gzip-compressed NBT file such as {@code level.dat}. */
    public static NamedTag readGzipFile(Path file) throws IOException {
        try (InputStream raw = Files.newInputStream(file); InputStream in = new GZIPInputStream(raw)) {
            byte[] data = in.readNBytes(MAX_FILE_BYTES + 1);
            if (data.length > MAX_FILE_BYTES) {
                throw new NbtFormatException(file + " is larger than " + MAX_FILE_BYTES + " bytes when decompressed");
            }
            return read(data);
        }
    }

    public static void writeGzipFile(Path file, NamedTag root, KeyOrder keyOrder) throws IOException {
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(file))) {
            write(root, keyOrder, out);
        }
    }

    /**
     * Reads straight from the byte array. A chunk is mostly long arrays, which hold its blocks, and
     * those are copied in one go.
     */
    private static final class Reader {

        private final byte[] data;
        private final ByteBuffer in;    // big-endian, as NBT is
        private int tags;

        Reader(byte[] data, int offset, int length) {
            this.data = data;
            this.in = ByteBuffer.wrap(data, offset, length);
        }

        NamedTag readRoot() throws IOException {
            TagType type = TagType.fromId(in.get() & 0xFF);
            if (type == TagType.END) {
                throw new NbtFormatException("root tag is END");
            }
            String name = readString();
            return new NamedTag(name, readPayload(type, 0));
        }

        /** A string in Java's modified UTF-8, with its length in front. */
        private String readString() throws IOException {
            int length = in.getShort() & 0xFFFF;
            int start = in.position();
            if (length > in.remaining()) {
                throw new EOFException();
            }
            in.position(start + length);
            for (int i = start; i < start + length; i++) {
                if (data[i] < 0) {
                    // Not plain ASCII: left to the JDK's decoder, which knows the two- and three-byte forms.
                    return new DataInputStream(new ByteArrayInputStream(data, start - 2, length + 2)).readUTF();
                }
            }
            return new String(data, start, length, StandardCharsets.ISO_8859_1);
        }

        private Tag readPayload(TagType type, int depth) throws IOException {
            if (depth > MAX_DEPTH) {
                throw new NbtFormatException("NBT nested deeper than " + MAX_DEPTH);
            }
            if (++tags > MAX_TAGS) {
                throw new NbtFormatException("NBT holds more than " + MAX_TAGS + " tags");
            }
            return switch (type) {
                case END -> throw new NbtFormatException("unexpected END tag");
                case BYTE -> new ByteTag(in.get());
                case SHORT -> new ShortTag(in.getShort());
                case INT -> new IntTag(in.getInt());
                case LONG -> new LongTag(in.getLong());
                case FLOAT -> new FloatTag(in.getFloat());
                case DOUBLE -> new DoubleTag(in.getDouble());
                case BYTE_ARRAY -> {
                    byte[] value = new byte[arrayLength(1)];
                    in.get(value);
                    yield new ByteArrayTag(value);
                }
                case STRING -> new StringTag(readString());
                case LIST -> readList(depth);
                case COMPOUND -> readCompound(depth);
                case INT_ARRAY -> {
                    int[] value = new int[arrayLength(4)];
                    in.asIntBuffer().get(value);
                    in.position(in.position() + value.length * 4);
                    yield new IntArrayTag(value);
                }
                case LONG_ARRAY -> {
                    long[] value = new long[arrayLength(8)];
                    in.asLongBuffer().get(value);
                    in.position(in.position() + value.length * 8);
                    yield new LongArrayTag(value);
                }
            };
        }

        /** Reads a length and checks it against the bytes left, so a corrupt length cannot exhaust memory. */
        private int arrayLength(int elementSize) throws IOException {
            int length = in.getInt();
            if (length < 0 || (long) length * elementSize > in.remaining()) {
                throw new NbtFormatException("array length " + length + " does not fit the data");
            }
            return length;
        }

        private ListTag readList(int depth) throws IOException {
            TagType elementType = TagType.fromId(in.get() & 0xFF);
            int length = in.getInt();
            // Every element takes at least this many bytes, so a length the data cannot hold is caught here.
            int smallest = switch (elementType) {
                case END -> 0;
                case BYTE, COMPOUND -> 1;
                case SHORT, STRING -> 2;
                case INT, FLOAT, BYTE_ARRAY, INT_ARRAY, LONG_ARRAY -> 4;
                case LIST -> 5;
                case LONG, DOUBLE -> 8;
            };
            if (length < 0 || (elementType == TagType.END ? length != 0 : (long) length * smallest > in.remaining())) {
                throw new NbtFormatException("list length " + length + " does not fit the data");
            }
            // The list grows as elements arrive: the declared length reserves nothing.
            List<Tag> elements = new ArrayList<>(Math.min(length, 1024));
            for (int i = 0; i < length; i++) {
                elements.add(readPayload(elementType, depth + 1));
            }
            return ListTag.of(elementType, elements);
        }

        private CompoundTag readCompound(int depth) throws IOException {
            CompoundTag.Builder builder = CompoundTag.builder();
            while (true) {
                TagType type = TagType.fromId(in.get() & 0xFF);
                if (type == TagType.END) {
                    return builder.build();
                }
                String key = readString();
                builder.put(key, readPayload(type, depth + 1));
            }
        }
    }

    private static void writePayload(Tag tag, KeyOrder keyOrder, DataOutputStream out) throws IOException {
        switch (tag) {
            case ByteTag t -> out.writeByte(t.value());
            case ShortTag t -> out.writeShort(t.value());
            case IntTag t -> out.writeInt(t.value());
            case LongTag t -> out.writeLong(t.value());
            case FloatTag t -> out.writeInt(Float.floatToRawIntBits(t.value()));
            case DoubleTag t -> out.writeLong(Double.doubleToRawLongBits(t.value()));
            case ByteArrayTag t -> {
                out.writeInt(t.size());
                out.write(t.bytes());
            }
            case StringTag t -> out.writeUTF(t.value());
            case ListTag t -> {
                out.writeByte(t.elementType().id());
                out.writeInt(t.size());
                for (Tag element : t) {
                    writePayload(element, keyOrder, out);
                }
            }
            case CompoundTag t -> {
                Map<String, Tag> entries = keyOrder == KeyOrder.SORTED ? new TreeMap<>(t.entries()) : t.entries();
                for (Map.Entry<String, Tag> entry : entries.entrySet()) {
                    out.writeByte(entry.getValue().type().id());
                    out.writeUTF(entry.getKey());
                    writePayload(entry.getValue(), keyOrder, out);
                }
                out.writeByte(TagType.END.id());
            }
            case IntArrayTag t -> {
                out.writeInt(t.size());
                ByteBuffer bytes = ByteBuffer.allocate(t.size() * 4);
                bytes.asIntBuffer().put(t.ints());
                out.write(bytes.array());
            }
            case LongArrayTag t -> {
                out.writeInt(t.size());
                ByteBuffer bytes = ByteBuffer.allocate(t.size() * 8);
                bytes.asLongBuffer().put(t.longs());
                out.write(bytes.array());
            }
        }
    }
}
