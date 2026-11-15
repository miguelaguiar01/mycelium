package mycelium.region;

import mycelium.nbt.CompoundTag;
import mycelium.nbt.NamedTag;
import mycelium.nbt.NbtIo;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Writes a complete region file.
 *
 * <p>Chunks are laid out in slot order with no gaps, so the layout depends only on the chunks
 * given. Timestamps are taken from the caller, never from the clock. A chunk that needs 256
 * sectors or more is written to a {@code c.X.Z.mcc} file next to the region file, as the game
 * does. The directory must not hold older files for the same region: a stale {@code .mcc} file
 * would be left behind.
 */
public final class RegionWriter {

    /** One chunk to encode and write. */
    public record Entry(int timestamp, CompoundTag root) {
    }

    /** One chunk to write, already compressed. */
    public record Record(int timestamp, Compression compression, byte[] payload) {

        /** Encodes and compresses a chunk. */
        public static Record encode(int timestamp, CompoundTag root, Compression compression, NbtIo.KeyOrder keyOrder)
                throws IOException {
            return new Record(timestamp, compression, compression.compress(NbtIo.write(NamedTag.of(root), keyOrder)));
        }

        /**
         * A chunk as it is stored in another region file, recompressed only if it is compressed
         * differently. A chunk whose stored bytes cannot be decompressed keeps them and its own
         * compression: a region file may mix compressions, and damage in a chunk nobody decoded is
         * carried over, not a reason to stop.
         */
        public static Record copy(int timestamp, RawSlot.Stored stored, Compression compression) throws IOException {
            if (stored.compression() == compression) {
                return new Record(timestamp, compression, stored.payload());
            }
            byte[] nbt;
            try {
                nbt = stored.nbt();
            } catch (IOException | RuntimeException e) {
                return new Record(timestamp, stored.compression(), stored.payload());
            }
            return new Record(timestamp, compression, compression.compress(nbt));
        }
    }

    private RegionWriter() {
    }

    /**
     * Encodes {@code chunks}, keyed by slot index, and writes them to {@code directory/r.X.Z.mca}.
     *
     * @param keyOrder order of compound keys in the written NBT
     * @return the region file
     */
    public static Path write(Path directory, RegionPos pos, SortedMap<Integer, Entry> chunks,
                             Compression compression, NbtIo.KeyOrder keyOrder) throws IOException {
        SortedMap<Integer, Record> records = new TreeMap<>();
        for (Map.Entry<Integer, Entry> chunk : chunks.entrySet()) {
            records.put(chunk.getKey(), Record.encode(chunk.getValue().timestamp(), chunk.getValue().root(), compression, keyOrder));
        }
        return write(directory, pos, records);
    }

    /**
     * Writes compressed chunks, keyed by slot index, to {@code directory/r.X.Z.mca}.
     *
     * @return the region file
     */
    public static Path write(Path directory, RegionPos pos, SortedMap<Integer, Record> chunks) throws IOException {
        Files.createDirectories(directory);
        ByteBuffer locations = ByteBuffer.allocate(RegionFile.SECTOR_BYTES);
        ByteBuffer timestamps = ByteBuffer.allocate(RegionFile.SECTOR_BYTES);
        Set<Integer> external = new HashSet<>();
        int sector = 2;
        for (Map.Entry<Integer, Record> chunk : chunks.entrySet()) {
            int index = chunk.getKey();
            if (index < 0 || index >= RegionPos.SLOTS) {
                throw new IllegalArgumentException("slot index " + index);
            }
            byte[] payload = chunk.getValue().payload();
            int stored = payload.length;
            if (sectorsFor(payload.length) >= RegionFile.EXTERNAL_SECTOR_THRESHOLD) {
                Files.write(directory.resolve(pos.externalFileName(index)), payload);
                external.add(index);
                stored = 0;
            }
            int sectors = sectorsFor(stored);
            locations.putInt(index * 4, sector << 8 | sectors);
            timestamps.putInt(index * 4, chunk.getValue().timestamp());
            sector += sectors;
        }
        // The chunks go to the file one after the other, in slot order; no second copy of them is built.
        Path file = directory.resolve(pos.fileName());
        byte[] padding = new byte[RegionFile.SECTOR_BYTES];
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(file), 1 << 16)) {
            out.write(locations.array());
            out.write(timestamps.array());
            for (Map.Entry<Integer, Record> chunk : chunks.entrySet()) {
                boolean outside = external.contains(chunk.getKey());
                byte[] payload = outside ? new byte[0] : chunk.getValue().payload();
                int compressionByte = chunk.getValue().compression().id() | (outside ? RegionFile.EXTERNAL_FLAG : 0);
                out.write(ByteBuffer.allocate(RegionFile.CHUNK_HEADER_BYTES).putInt(payload.length + 1)
                        .put((byte) compressionByte).array());
                out.write(payload);
                int written = RegionFile.CHUNK_HEADER_BYTES + payload.length;
                out.write(padding, 0, sectorsFor(payload.length) * RegionFile.SECTOR_BYTES - written);
            }
        }
        return file;
    }

    private static int sectorsFor(int payloadLength) {
        return (payloadLength + RegionFile.CHUNK_HEADER_BYTES + RegionFile.SECTOR_BYTES - 1) / RegionFile.SECTOR_BYTES;
    }
}
