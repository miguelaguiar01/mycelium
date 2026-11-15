package mycelium.region;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import mycelium.nbt.ByteArrayTag;
import mycelium.nbt.CompoundTag;
import mycelium.nbt.IntTag;
import mycelium.nbt.NamedTag;
import mycelium.nbt.NbtIo;
import mycelium.testing.Fixtures;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

class RegionFileTest {

    @TempDir
    Path temp;

    // ---- real files -------------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource({"probe-zlib, ZLIB, 870", "probe-lz4, LZ4, 342", "probe-none, NONE, 342"})
    void fixtureChunksAllReadWithTheCompressionTheWorldWasWrittenWith(String world, Compression expected, int chunks) {
        for (String storage : Fixtures.STORAGE) {
            Set<Compression> seen = EnumSet.noneOf(Compression.class);
            List<Fixtures.FixtureChunk> all = Fixtures.chunks(world, storage);
            for (Fixtures.FixtureChunk chunk : all) {
                seen.add(chunk.chunk().compression());
                assertEquals("", chunk.chunk().rootName(), "the game writes an empty root name");
            }
            assertEquals(Set.of(expected), seen, world + "/" + storage);
            if (storage.equals("region")) {
                assertEquals(chunks, all.size(), world + " chunk count");
            }
        }
    }

    @Test
    void oversizedChunkIsReadFromItsExternalFile() {
        List<Fixtures.FixtureChunk> external = Fixtures.chunks("probe-none", "region").stream()
                .filter(chunk -> chunk.chunk().external()).toList();
        assertEquals(1, external.size());
        Fixtures.FixtureChunk chunk = external.get(0);
        assertEquals(RegionPos.slotIndex(5, 4), chunk.slot());
        assertEquals("c.5.4.mcc", chunk.region().pos().externalFileName(chunk.slot()));
        assertEquals(new IntTag(5), chunk.root().get("xPos"));
        assertEquals(new IntTag(4), chunk.root().get("zPos"));
        assertTrue(chunk.region().rawNbt(chunk.slot()).length > 255 * 4096, "it really is too large for a region file");
    }

    @Test
    void slotIndexFollowsChunkCoordinates() {
        for (Fixtures.FixtureChunk chunk : Fixtures.allChunks("region")) {
            int x = ((IntTag) chunk.root().get("xPos")).value();
            int z = ((IntTag) chunk.root().get("zPos")).value();
            assertEquals(chunk.slot(), RegionPos.slotIndex(x, z));
            assertEquals(chunk.region().pos(), RegionPos.ofChunk(x, z));
            assertEquals(x, chunk.region().pos().chunkX(chunk.slot()));
            assertEquals(z, chunk.region().pos().chunkZ(chunk.slot()));
        }
    }

    // ---- writing ----------------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(Compression.class)
    void rewritingAFixtureRegionKeepsEveryChunkAndTimestamp(Compression compression) throws IOException {
        for (String world : Fixtures.WORLDS) {
            for (String storage : Fixtures.STORAGE) {
                for (RegionFile original : Fixtures.regions(world, storage)) {
                    Path directory = temp.resolve(world + "-" + storage + "-" + compression);
                    SortedMap<Integer, RegionWriter.Entry> chunks = new TreeMap<>();
                    for (int slot : original.usedSlots()) {
                        RegionSlot.Chunk chunk = (RegionSlot.Chunk) original.slot(slot);
                        chunks.put(slot, new RegionWriter.Entry(chunk.timestamp(), chunk.root()));
                    }
                    Path written = RegionWriter.write(directory, original.pos(), chunks, compression, NbtIo.KeyOrder.SORTED);
                    assertEquals(0, Files.size(written) % 4096, "file length is a whole number of sectors");

                    RegionFile copy = RegionFile.read(written);
                    assertEquals(original.usedSlots(), copy.usedSlots());
                    for (int slot : original.usedSlots()) {
                        RegionSlot.Chunk before = (RegionSlot.Chunk) original.slot(slot);
                        RegionSlot.Chunk after = assertInstanceOf(RegionSlot.Chunk.class, copy.slot(slot));
                        assertEquals(before.root(), after.root());
                        assertEquals(before.timestamp(), after.timestamp());
                        assertEquals(compression, after.compression());
                    }
                }
            }
        }
    }

    @Test
    void writerMovesAnOversizedChunkToAnExternalFile() throws IOException {
        byte[] noise = new byte[1_200_000];
        new Random(1).nextBytes(noise);
        CompoundTag big = CompoundTag.of("noise", new ByteArrayTag(noise));
        CompoundTag small = CompoundTag.of("value", new IntTag(7));
        RegionPos pos = new RegionPos(-2, 3);
        SortedMap<Integer, RegionWriter.Entry> chunks = new TreeMap<>();
        chunks.put(0, new RegionWriter.Entry(100, small));
        chunks.put(33, new RegionWriter.Entry(200, big));
        chunks.put(1023, new RegionWriter.Entry(300, small));

        for (Compression compression : Compression.values()) {
            Path directory = temp.resolve("external-" + compression);
            RegionFile region = RegionFile.read(RegionWriter.write(directory, pos, chunks, compression, NbtIo.KeyOrder.STORED));
            // Slot 33 of region (-2, 3) is chunk (-63, 97).
            Path external = directory.resolve("c.-63.97.mcc");
            assertTrue(Files.exists(external), compression + ": external file exists");
            assertEquals(4 * 4096 + 4096, Files.size(directory.resolve("r.-2.3.mca")),
                    "header, two small chunks and a one-sector stub");
            RegionSlot.Chunk read = assertInstanceOf(RegionSlot.Chunk.class, region.slot(33));
            assertTrue(read.external());
            assertEquals(big, read.root());
            assertEquals(200, read.timestamp());
            assertFalse(((RegionSlot.Chunk) region.slot(0)).external());
            assertEquals(small, ((RegionSlot.Chunk) region.slot(1023)).root());

            Files.delete(external);
            RegionSlot.Corrupt missing = assertInstanceOf(RegionSlot.Corrupt.class, region.slot(33));
            assertTrue(missing.reason().contains("c.-63.97.mcc"), missing.reason());
        }
    }

    @Test
    void layoutDependsOnlyOnTheChunksGiven() throws IOException {
        SortedMap<Integer, RegionWriter.Entry> chunks = new TreeMap<>();
        for (int slot : new int[] {5, 700, 31, 32}) {
            chunks.put(slot, new RegionWriter.Entry(slot, CompoundTag.of("slot", new IntTag(slot))));
        }
        Path first = RegionWriter.write(temp.resolve("a"), new RegionPos(0, 0), chunks, Compression.ZLIB, NbtIo.KeyOrder.SORTED);
        Path second = RegionWriter.write(temp.resolve("b"), new RegionPos(0, 0), chunks, Compression.ZLIB, NbtIo.KeyOrder.SORTED);
        assertEquals(-1, Files.mismatch(first, second));
        ByteBuffer header = ByteBuffer.wrap(Files.readAllBytes(first));
        assertEquals(2 << 8 | 1, header.getInt(5 * 4), "lowest slot comes first, right after the header");
        assertEquals(3 << 8 | 1, header.getInt(31 * 4));
        assertEquals(4 << 8 | 1, header.getInt(32 * 4));
        assertEquals(5 << 8 | 1, header.getInt(700 * 4));
        assertEquals(700, header.getInt(4096 + 700 * 4), "timestamp is the one given");
    }

    // ---- damaged files ----------------------------------------------------------------------

    /** A region file whose slot 0 points at {@code record}, placed at {@code sector}. */
    private Path regionWith(byte[] record, int sector, int sectorCount, int fileSectors) throws IOException {
        ByteBuffer file = ByteBuffer.allocate(fileSectors * 4096);
        if (record != null && sector * 4096 + record.length <= file.capacity()) {
            file.put(sector * 4096, record);
        }
        file.putInt(0, sector << 8 | sectorCount);
        file.putInt(4096, 1234);
        Path path = Files.createTempDirectory(temp, "damaged").resolve("r.0.0.mca");
        Files.write(path, file.array());
        return path;
    }

    private static byte[] record(int length, int compression, byte[] payload) {
        return ByteBuffer.allocate(5 + payload.length).putInt(length).put((byte) compression).put(payload).array();
    }

    private static byte[] zlib(CompoundTag root) throws IOException {
        return Compression.ZLIB.compress(NbtIo.write(NamedTag.of(root)));
    }

    private static String corruptReason(Path file) throws IOException {
        RegionFile region = RegionFile.read(file);
        assertEquals(List.of(0), region.usedSlots(), "a damaged slot still counts as used");
        RegionSlot.Corrupt corrupt = assertInstanceOf(RegionSlot.Corrupt.class, region.slot(0));
        assertEquals(1234, corrupt.timestamp());
        return corrupt.reason();
    }

    @Test
    void aGoodSyntheticChunkReads() throws IOException {
        byte[] payload = zlib(CompoundTag.of("ok", new IntTag(1)));
        RegionFile region = RegionFile.read(regionWith(record(payload.length + 1, 2, payload), 2, 1, 3));
        RegionSlot.Chunk chunk = assertInstanceOf(RegionSlot.Chunk.class, region.slot(0));
        assertEquals(CompoundTag.of("ok", new IntTag(1)), chunk.root());
        assertInstanceOf(RegionSlot.Empty.class, region.slot(1));
    }

    @Test
    void damagedSlotsAreReportedAsCorruptNeverAsEmpty() throws IOException {
        byte[] good = zlib(CompoundTag.of("ok", new IntTag(1)));

        assertTrue(corruptReason(regionWith(record(good.length + 1, 2, good), 1, 1, 3)).contains("overlaps the header"));
        assertTrue(corruptReason(regionWith(record(good.length + 1, 2, good), 2, 0, 3)).contains("sector count"));
        assertTrue(corruptReason(regionWith(record(good.length + 1, 2, good), 9, 1, 3)).contains("past the end"));
        assertTrue(corruptReason(regionWith(record(0, 2, good), 2, 1, 3)).contains("declared length is 0"));
        assertTrue(corruptReason(regionWith(record(-5, 2, good), 2, 1, 3)).contains("declared length is -5"));
        assertTrue(corruptReason(regionWith(record(5000, 2, good), 2, 1, 3)).contains("does not fit"));
        assertTrue(corruptReason(regionWith(record(good.length + 1, 99, good), 2, 1, 3)).contains("unsupported compression type 99"));
        assertTrue(corruptReason(regionWith(record(good.length + 1, 127, good), 2, 1, 3)).contains("unsupported compression type 127"));
        assertTrue(corruptReason(regionWith(record(good.length + 1, 0x82, good), 2, 1, 3)).contains("both an internal and an external"));
        assertTrue(corruptReason(regionWith(record(1, 0x82, new byte[0]), 2, 1, 3)).contains("c.0.0.mcc"));

        byte[] garbage = new byte[64];
        new Random(2).nextBytes(garbage);
        assertTrue(corruptReason(regionWith(record(garbage.length + 1, 2, garbage), 2, 1, 3)).contains("cannot decompress ZLIB"));
        assertTrue(corruptReason(regionWith(record(garbage.length + 1, 1, garbage), 2, 1, 3)).contains("cannot decompress GZIP"));
        assertTrue(corruptReason(regionWith(record(garbage.length + 1, 4, garbage), 2, 1, 3)).contains("cannot decompress LZ4"));

        byte[] cut = java.util.Arrays.copyOf(good, good.length - 4);
        assertTrue(corruptReason(regionWith(record(cut.length + 1, 2, cut), 2, 1, 3)).contains("cannot decompress ZLIB"));

        byte[] notNbt = Compression.ZLIB.compress(new byte[] {10, 0, 0, 3, 0, 1, 'a'});
        assertTrue(corruptReason(regionWith(record(notNbt.length + 1, 2, notNbt), 2, 1, 3)).contains("invalid NBT"));
        byte[] notCompound = Compression.ZLIB.compress(NbtIo.write(NamedTag.of(new IntTag(1))));
        assertTrue(corruptReason(regionWith(record(notCompound.length + 1, 2, notCompound), 2, 1, 3)).contains("not a compound"));
    }

    @Test
    void truncatedHeaderFailsTheWholeFileAndAnEmptyFileIsAnEmptyRegion() throws IOException {
        Path cut = temp.resolve("r.1.1.mca");
        Files.write(cut, new byte[5000]);
        assertThrows(RegionFormatException.class, () -> RegionFile.read(cut));

        Path empty = temp.resolve("r.2.2.mca");
        Files.write(empty, new byte[0]);
        RegionFile region = RegionFile.read(empty);
        assertEquals(List.of(), region.usedSlots());
        assertInstanceOf(RegionSlot.Empty.class, region.slot(0));

        Path misnamed = temp.resolve("region.mca");
        Files.write(misnamed, new byte[8192]);
        assertThrows(RegionFormatException.class, () -> RegionFile.read(misnamed));
    }

    @Test
    void theLastChunkOfAFileThatWasNotPaddedStillReads() throws IOException {
        // The game pads a region file only when it closes it, and loads a chunk whose payload is all there.
        // Checked in the game: a file cut right after its last chunk's payload loads with a clean log.
        byte[] good = zlib(CompoundTag.of("ok", new IntTag(1)));
        Path whole = regionWith(record(good.length + 1, 2, good), 2, 1, 3);
        byte[] bytes = Files.readAllBytes(whole);
        Path cutAfterPayload = Files.createTempDirectory(temp, "unpadded").resolve("r.0.0.mca");
        Files.write(cutAfterPayload, java.util.Arrays.copyOf(bytes, 2 * 4096 + 5 + good.length));
        for (RegionFile region : List.of(RegionFile.read(cutAfterPayload), RegionFile.open(cutAfterPayload))) {
            try (region) {
                assertEquals(CompoundTag.of("ok", new IntTag(1)),
                        assertInstanceOf(RegionSlot.Chunk.class, RegionFile.decode(region.raw(0))).root());
            }
        }
        // Two sectors allotted, one in the file: fine as long as the payload fits what is there.
        assertInstanceOf(RegionSlot.Chunk.class, RegionFile.read(regionWith(record(good.length + 1, 2, good), 2, 2, 3)).slot(0));

        Path cutInsidePayload = Files.createTempDirectory(temp, "cut").resolve("r.0.0.mca");
        Files.write(cutInsidePayload, java.util.Arrays.copyOf(bytes, 2 * 4096 + 5 + good.length - 3));
        assertTrue(corruptReason(cutInsidePayload).contains("runs past the end of the file"));
        Path cutInsideHeader = Files.createTempDirectory(temp, "cut").resolve("r.0.0.mca");
        Files.write(cutInsideHeader, java.util.Arrays.copyOf(bytes, 2 * 4096 + 3));
        assertTrue(corruptReason(cutInsideHeader).contains("past the end"));
    }

    @Test
    void aStoredChunkThatCannotBeRecompressedIsCopiedAsItIs() throws IOException {
        byte[] garbage = new byte[64];
        new Random(2).nextBytes(garbage);
        RawSlot.Stored damaged = assertInstanceOf(RawSlot.Stored.class,
                RegionFile.read(regionWith(record(garbage.length + 1, 4, garbage), 2, 1, 3)).raw(0));
        // A chunk nobody decoded is carried over with its damage; it must not stop the run.
        RegionWriter.Record copy = RegionWriter.Record.copy(7, damaged, Compression.ZLIB);
        assertEquals(Compression.LZ4, copy.compression());
        assertArrayEquals(garbage, copy.payload());
        SortedMap<Integer, RegionWriter.Record> records = new TreeMap<>(java.util.Map.of(0, copy));
        Path written = RegionWriter.write(Files.createTempDirectory(temp, "copied"), new RegionPos(0, 0), records);
        assertTrue(assertInstanceOf(RegionSlot.Corrupt.class, RegionFile.read(written).slot(0)).reason()
                .contains("cannot decompress LZ4"));
    }

    @Test
    void regionFileNamesParse() {
        // Only the name the game writes: any other spelling would be opened under a name that is not there.
        assertNull(RegionPos.parse("r.007.0.mca"));
        assertNull(RegionPos.parse("r.-0.0.mca"));
        assertNull(RegionPos.parse("r.99999999.0.mca"));
        assertEquals(new RegionPos(-3, 12), RegionPos.parse("r.-3.12.mca"));
        assertEquals("r.-3.12.mca", new RegionPos(-3, 12).fileName());
        assertNull(RegionPos.parse("r.1.2.mcc"));
        assertNull(RegionPos.parse("c.1.2.mca"));
        assertNull(RegionPos.parse("r.1.mca"));
        assertEquals(new RegionPos(-1, -1), RegionPos.ofChunk(-1, -32));
        assertEquals(31 * 32 + 31, RegionPos.slotIndex(-1, -1));
    }


    @ParameterizedTest
    @EnumSource(value = Compression.class, names = {"ZLIB", "LZ4", "NONE"})
    void storedChunksCanBeReadOnDemandAndCopiedWithoutDecodingThem(Compression compression) throws IOException {
        for (String world : Fixtures.WORLDS) {
            for (RegionFile original : Fixtures.regions(world, "region")) {
                SortedMap<Integer, RegionWriter.Record> records = new TreeMap<>();
                try (RegionFile onDemand = RegionFile.open(original.file())) {
                    for (int slot = 0; slot < RegionPos.SLOTS; slot++) {
                        assertEquals(original.has(slot), onDemand.has(slot));
                        assertEquals(original.slot(slot), onDemand.slot(slot), "the same chunk as when the whole file is read");
                        if (onDemand.raw(slot) instanceof mycelium.region.RawSlot.Stored stored) {
                            assertTrue(stored.sameBytes((mycelium.region.RawSlot.Stored) original.raw(slot)));
                            records.put(slot, RegionWriter.Record.copy(stored.timestamp(), stored, compression));
                            // A chunk is copied as it is if it is already compressed the way that was asked for.
                            assertEquals(stored.compression() == compression,
                                    java.util.Arrays.equals(stored.payload(), records.get(slot).payload()));
                        }
                    }
                }
                RegionFile copy = RegionFile.read(RegionWriter.write(temp.resolve(world + "-copied-" + compression), original.pos(), records));
                assertEquals(original.usedSlots(), copy.usedSlots());
                for (int slot : original.usedSlots()) {
                    RegionSlot.Chunk before = (RegionSlot.Chunk) original.slot(slot);
                    RegionSlot.Chunk after = assertInstanceOf(RegionSlot.Chunk.class, copy.slot(slot));
                    assertEquals(before.root(), after.root());
                    assertEquals(before.timestamp(), after.timestamp());
                    assertEquals(compression, after.compression());
                }
            }
        }
    }
}
