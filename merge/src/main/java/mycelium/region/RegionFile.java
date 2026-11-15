package mycelium.region;

import mycelium.nbt.CompoundTag;
import mycelium.nbt.NamedTag;
import mycelium.nbt.NbtFormatException;
import mycelium.nbt.NbtIo;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * A region file ({@code r.X.Z.mca}).
 *
 * <p>The file starts with 1,024 location entries and 1,024 timestamps. Chunks follow in
 * 4,096-byte sectors, each with a length, a compression byte and the compressed NBT. A chunk too
 * large for 255 sectors lives in a {@code c.X.Z.mcc} file next to the region file. The same
 * format is used for chunk, entity and POI storage.
 *
 * <p>{@link #read} loads the whole file. {@link #open} loads only the header and reads each chunk
 * from the file when it is asked for, which keeps memory use independent of the file's size; a
 * file opened that way must be closed.
 *
 * <p>A slot that is allocated but unreadable is returned as corrupt, never as empty, so callers
 * can refuse to merge it.
 */
public final class RegionFile implements AutoCloseable {

    static final int SECTOR_BYTES = 4096;
    static final int HEADER_BYTES = 2 * SECTOR_BYTES;
    static final int CHUNK_HEADER_BYTES = 5;
    static final int EXTERNAL_FLAG = 0x80;
    /** An external chunk file larger than this is not read; no chunk of the game comes near it. */
    private static final long MAX_EXTERNAL_BYTES = 1 << 28;
    /** A chunk needing this many sectors or more goes to an external file. */
    static final int EXTERNAL_SECTOR_THRESHOLD = 256;

    private final Path file;
    private final RegionPos pos;
    private final byte[] data;            // the whole file, or only its header if there is a channel
    private final FileChannel channel;    // null if the whole file is in memory
    private final long size;

    private RegionFile(Path file, RegionPos pos, byte[] data, FileChannel channel, long size) {
        this.file = file;
        this.pos = pos;
        this.data = data;
        this.channel = channel;
        this.size = size;
    }

    /**
     * Reads a whole region file into memory. An empty file is a region with no chunks.
     *
     * @throws RegionFormatException if the name is not {@code r.X.Z.mca} or the header is cut short
     */
    public static RegionFile read(Path file) throws IOException {
        RegionPos pos = position(file);
        byte[] data = Files.readAllBytes(file);
        checkHeader(file, data.length);
        return new RegionFile(file, pos, data, null, data.length);
    }

    /**
     * Opens a region file and reads its header. Chunks are read when asked for. Reading is safe
     * from several threads. The caller must close the file.
     *
     * @throws RegionFormatException if the name is not {@code r.X.Z.mca} or the header is cut short
     */
    public static RegionFile open(Path file) throws IOException {
        RegionPos pos = position(file);
        FileChannel channel = FileChannel.open(file, StandardOpenOption.READ);
        try {
            long size = channel.size();
            checkHeader(file, size);
            byte[] header = new byte[(int) Math.min(size, HEADER_BYTES)];
            readFully(channel, ByteBuffer.wrap(header), 0);
            return new RegionFile(file, pos, header, channel, size);
        } catch (IOException | RuntimeException e) {
            channel.close();
            throw e;
        }
    }

    private static RegionPos position(Path file) throws RegionFormatException {
        RegionPos pos = RegionPos.parse(file.getFileName().toString());
        if (pos == null) {
            throw new RegionFormatException(file + " is not named like a region file");
        }
        return pos;
    }

    private static void checkHeader(Path file, long size) throws RegionFormatException {
        if (size != 0 && size < HEADER_BYTES) {
            throw new RegionFormatException(file + " has a truncated header: " + size + " bytes");
        }
    }

    private static void readFully(FileChannel channel, ByteBuffer buffer, long position) throws IOException {
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, position + buffer.position());
            if (read < 0) {
                throw new IOException("unexpected end of file");
            }
        }
    }

    /** Closes the file if it was opened with {@link #open}. */
    @Override
    public void close() throws IOException {
        if (channel != null) {
            channel.close();
        }
    }

    public Path file() {
        return file;
    }

    public RegionPos pos() {
        return pos;
    }

    /** The indexes of all slots that are not empty, ascending. */
    public List<Integer> usedSlots() {
        List<Integer> used = new ArrayList<>();
        for (int index = 0; index < RegionPos.SLOTS; index++) {
            if (location(index) != 0) {
                used.add(index);
            }
        }
        return used;
    }

    public RegionSlot slot(int chunkX, int chunkZ) {
        return slot(RegionPos.slotIndex(chunkX, chunkZ));
    }

    /** Reads and decodes the slot at {@code index}, which is {@code (z & 31) * 32 + (x & 31)}. */
    public RegionSlot slot(int index) {
        return decode(raw(index), null);
    }

    /** Whether the slot at {@code index} is allocated, readable or not. */
    public boolean has(int index) {
        return location(index) != 0;
    }

    /** The save time of a slot from the header, or 0 if the slot is empty. */
    public int timestamp(int index) {
        return location(index) == 0 ? 0 : ByteBuffer.wrap(data, SECTOR_BYTES + index * 4, 4).getInt();
    }

    /**
     * The compressed bytes of the slot at {@code index}, without decoding them.
     *
     * @throws UncheckedIOException if the file was opened with {@link #open} and cannot be read
     */
    public RawSlot raw(int index) {
        int location = location(index);
        if (location == 0) {
            return new RawSlot.Empty();
        }
        int timestamp = timestamp(index);
        int sector = location >>> 8;
        int sectorCount = location & 0xFF;
        if (sector < 2) {
            return new RawSlot.Corrupt(timestamp, "sector " + sector + " overlaps the header");
        }
        if (sectorCount == 0) {
            return new RawSlot.Corrupt(timestamp, "sector count is 0");
        }
        long start = (long) sector * SECTOR_BYTES;
        int allocated = sectorCount * SECTOR_BYTES;
        // The game pads a region file to whole sectors only when it closes it. The last chunk of a file
        // that was copied while the game ran, or left by a crash, can stop short of its last sector, and
        // the game still loads it. So a chunk is whole if its payload is there, whatever the padding.
        int present = (int) Math.min(allocated, size - start);
        if (present < CHUNK_HEADER_BYTES) {
            return new RawSlot.Corrupt(timestamp, "sectors " + sector + "+" + sectorCount + " are past the end of the file");
        }
        byte[] record = data;
        int offset = (int) start;
        if (channel != null) {
            record = new byte[present];
            offset = 0;
            try {
                readFully(channel, ByteBuffer.wrap(record), start);
            } catch (IOException e) {
                throw new UncheckedIOException(file + ": " + e.getMessage(), e);
            }
        }
        ByteBuffer header = ByteBuffer.wrap(record, offset, CHUNK_HEADER_BYTES);
        int length = header.getInt();
        int compressionByte = header.get() & 0xFF;
        if (length <= 0) {
            return new RawSlot.Corrupt(timestamp, "declared length is " + length);
        }
        boolean external = (compressionByte & EXTERNAL_FLAG) != 0;
        Compression compression = Compression.fromId(compressionByte & ~EXTERNAL_FLAG);
        if (compression == null) {
            return new RawSlot.Corrupt(timestamp, "unsupported compression type " + (compressionByte & ~EXTERNAL_FLAG));
        }
        int payloadLength = length - 1;
        if (external) {
            if (payloadLength != 0) {
                return new RawSlot.Corrupt(timestamp, "chunk has both an internal and an external stream");
            }
            Path externalFile = file.resolveSibling(pos.externalFileName(index));
            try {
                if (Files.size(externalFile) > MAX_EXTERNAL_BYTES) {
                    return new RawSlot.Corrupt(timestamp, "external file " + externalFile.getFileName() + " is too large");
                }
                byte[] payload = Files.readAllBytes(externalFile);
                return new RawSlot.Stored(timestamp, compression, true, payload, 0, payload.length);
            } catch (IOException e) {
                return new RawSlot.Corrupt(timestamp, "external file " + externalFile.getFileName() + " cannot be read");
            }
        }
        if (payloadLength > allocated - CHUNK_HEADER_BYTES) {
            return new RawSlot.Corrupt(timestamp, "declared length " + length + " does not fit " + sectorCount + " sectors");
        }
        if (payloadLength > present - CHUNK_HEADER_BYTES) {
            return new RawSlot.Corrupt(timestamp, "declared length " + length + " runs past the end of the file");
        }
        return new RawSlot.Stored(timestamp, compression, false, record, offset + CHUNK_HEADER_BYTES, payloadLength);
    }

    /** Decompresses and parses a slot read with {@link #raw}. */
    public static RegionSlot decode(RawSlot raw) {
        return decode(raw, null);
    }

    private static RegionSlot decode(RawSlot raw, byte[][] rawOut) {
        if (raw instanceof RawSlot.Empty) {
            return new RegionSlot.Empty();
        }
        if (raw instanceof RawSlot.Corrupt corrupt) {
            return new RegionSlot.Corrupt(corrupt.timestamp(), corrupt.reason());
        }
        RawSlot.Stored stored = (RawSlot.Stored) raw;
        int timestamp = stored.timestamp();
        byte[] nbt;
        try {
            nbt = stored.nbt();
        } catch (IOException | RuntimeException e) {
            return new RegionSlot.Corrupt(timestamp, "cannot decompress " + stored.compression() + " data: " + e.getMessage());
        }
        if (rawOut != null) {
            rawOut[0] = nbt;
        }
        NamedTag root;
        try {
            root = NbtIo.read(nbt);
        } catch (NbtFormatException e) {
            return new RegionSlot.Corrupt(timestamp, "invalid NBT: " + e.getMessage());
        }
        if (!(root.tag() instanceof CompoundTag compound)) {
            return new RegionSlot.Corrupt(timestamp, "root tag is a " + root.tag().type() + ", not a compound");
        }
        return new RegionSlot.Chunk(timestamp, stored.compression(), stored.external(), root.name(), compound);
    }

    /**
     * The decompressed NBT bytes of a slot exactly as stored, or null if the slot is empty or its
     * data cannot be decompressed. For tools and tests that compare bytes.
     */
    public byte[] rawNbt(int index) {
        byte[][] raw = new byte[1][];
        decode(raw(index), raw);
        return raw[0];
    }

    private int location(int index) {
        if (index < 0 || index >= RegionPos.SLOTS) {
            throw new IndexOutOfBoundsException("slot index " + index);
        }
        return data.length == 0 ? 0 : ByteBuffer.wrap(data, index * 4, 4).getInt();
    }
}
