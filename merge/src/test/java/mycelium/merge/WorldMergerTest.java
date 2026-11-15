package mycelium.merge;

import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.ParameterizedTest;
import mycelium.report.Category;
import mycelium.report.MergeReport;
import mycelium.testing.TestChunks;
import mycelium.testing.CompareWorlds;
import mycelium.region.RawSlot;
import static mycelium.testing.TestChunks.set;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import mycelium.chunk.BlockState;
import mycelium.chunk.ChunkCodec;
import mycelium.chunk.ChunkData;
import mycelium.cli.Main;
import mycelium.json.Json;
import mycelium.nbt.CompoundTag;
import mycelium.nbt.IntTag;
import mycelium.nbt.NamedTag;
import mycelium.nbt.NbtIo;
import mycelium.region.Compression;
import mycelium.region.RegionFile;
import mycelium.region.RegionPos;
import mycelium.region.RegionSlot;
import mycelium.region.RegionWriter;
import mycelium.testing.Fixtures;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The whole tool, from world folders to a merged world and a report, on the real fixture world. */
class WorldMergerTest {

    private static final String DIM = "dimensions/minecraft/overworld/";
    private static final Path FIXTURE = Fixtures.world("probe-zlib");

    @TempDir
    Path temp;

    private Path copyOfFixture(String name) throws IOException {
        Path target = temp.resolve(name);
        try (Stream<Path> walk = Files.walk(FIXTURE)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                Path copy = target.resolve(FIXTURE.relativize(file));
                Files.createDirectories(copy.getParent());
                Files.copy(file, copy);
            }
        }
        return target;
    }

    private static RegionFile region(Path world, String storage) throws IOException {
        return region(world, DIM, storage);
    }

    private static RegionFile region(Path world, String dimension, String storage) throws IOException {
        return RegionFile.read(world.resolve(dimension + storage + "/r.1.1.mca"));
    }

    private static ChunkData chunk(Path world, int x, int z) throws Exception {
        return chunk(world, DIM, x, z);
    }

    private static ChunkData chunk(Path world, String dimension, int x, int z) throws Exception {
        return ChunkCodec.decode(((RegionSlot.Chunk) region(world, dimension, "region").slot(x, z)).root());
    }

    /** Rewrites one chunk of a world the way a play session would change it. */
    private static void edit(Path world, int x, int z, UnaryOperator<ChunkData> change) throws Exception {
        edit(world, DIM, x, z, change);
    }

    /** Changes one chunk and leaves every other chunk of its region file as it is stored. */
    private static void edit(Path world, String dimension, int x, int z, UnaryOperator<ChunkData> change) throws Exception {
        RegionFile region = region(world, dimension, "region");
        SortedMap<Integer, RegionWriter.Record> chunks = new TreeMap<>();
        for (int slot : region.usedSlots()) {
            RawSlot.Stored stored = (RawSlot.Stored) region.raw(slot);
            if (slot == RegionPos.slotIndex(x, z)) {
                RegionSlot.Chunk read = (RegionSlot.Chunk) region.slot(slot);
                chunks.put(slot, RegionWriter.Record.encode(read.timestamp() + 60,
                        ChunkCodec.encode(change.apply(ChunkCodec.decode(read.root()))), Compression.ZLIB, NbtIo.KeyOrder.STORED));
            } else {
                chunks.put(slot, RegionWriter.Record.copy(stored.timestamp(), stored, stored.compression()));
            }
        }
        RegionWriter.write(world.resolve(dimension + "region"), region.pos(), chunks);
    }

    /** Gives a world a second dimension: a copy of its overworld under another id's folder. */
    private static void addDimension(Path world, String folder) throws IOException {
        Path from = world.resolve(DIM);
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                Path copy = world.resolve(folder).resolve(from.relativize(file));
                Files.createDirectories(copy.getParent());
                Files.copy(file, copy);
            }
        }
    }

    /** Rewrites the {@code data} compound of a {@code {DataVersion, data}} file, or {@code Data} of level.dat. */
    private static void editData(Path world, String file, UnaryOperator<CompoundTag> change) throws IOException {
        NamedTag root = NbtIo.readGzipFile(world.resolve(file));
        String key = file.equals("level.dat") ? "Data" : "data";
        CompoundTag compound = (CompoundTag) root.tag();
        NbtIo.writeGzipFile(world.resolve(file), NamedTag.of(compound.with(key, change.apply((CompoundTag) compound.get(key)))),
                NbtIo.KeyOrder.STORED);
    }

    private static CompoundTag data(Path world, String file) throws IOException {
        return (CompoundTag) ((CompoundTag) NbtIo.readGzipFile(world.resolve(file)).tag()).get(file.equals("level.dat") ? "Data" : "data");
    }

    private record Run(int exit, String out, String err) {
    }

    private Run run(Path base, Path ours, Path theirs, String newer, Path out, String... more) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        String[] args = Stream.concat(Stream.of("--base", base.toString(), "--ours", ours.toString(), "--theirs",
                theirs.toString(), "--newer", newer, "--out", out.toString()), Stream.of(more)).toArray(String[]::new);
        int exit = Main.run(args, new PrintStream(stdout), new PrintStream(stderr));
        return new Run(exit, stdout.toString(), stderr.toString());
    }

    @Test
    void mergingAWorldWithItselfGivesTheSameWorld() throws Exception {
        Path out = temp.resolve("out");
        Path report = temp.resolve("report.json");
        Run run = run(FIXTURE, FIXTURE, FIXTURE, "ours", out, "--report", report.toString());
        assertEquals(0, run.exit(), run.err());
        assertTrue(run.out().contains("0 conflicts, 0 resolved, 0 refused"), run.out());

        RegionFile before = region(FIXTURE, "region");
        RegionFile after = region(out, "region");
        assertEquals(before.usedSlots(), after.usedSlots());
        assertEquals(870, after.usedSlots().size());
        for (int slot : before.usedSlots()) {
            RegionSlot.Chunk was = (RegionSlot.Chunk) before.slot(slot);
            RegionSlot.Chunk now = (RegionSlot.Chunk) after.slot(slot);
            assertEquals(ChunkCodec.decode(was.root()), ChunkCodec.decode(now.root()), "slot " + slot);
            assertTrue(((RawSlot.Stored) after.raw(slot)).sameBytes((RawSlot.Stored) before.raw(slot)),
                    "a chunk that is the same on both sides is copied as stored");
            assertEquals(was.timestamp(), now.timestamp(), "timestamps come from the inputs, not the clock");
        }
        for (String storage : List.of("entities", "poi")) {
            RegionFile was = region(FIXTURE, storage);
            RegionFile now = region(out, storage);
            assertEquals(was.usedSlots(), now.usedSlots(), storage);
            for (int slot : was.usedSlots()) {
                CompoundTag a = ((RegionSlot.Chunk) was.slot(slot)).root();
                CompoundTag b = ((RegionSlot.Chunk) now.slot(slot)).root();
                if (storage.equals("poi")) {
                    assertEquals(a, b);
                } else {
                    assertEquals(new HashSet<>(((mycelium.nbt.ListTag) a.get("Entities")).elements()),
                            new HashSet<>(((mycelium.nbt.ListTag) b.get("Entities")).elements()));
                }
            }
        }
        for (String file : List.of("level.dat", "data/minecraft/game_rules.dat", "data/minecraft/world_clocks.dat",
                "data/minecraft/weather.dat", DIM + "data/minecraft/chunk_tickets.dat")) {
            assertEquals(NbtIo.readGzipFile(FIXTURE.resolve(file)), NbtIo.readGzipFile(out.resolve(file)), file);
        }
        assertFalse(Files.exists(out.resolve(DIM + "data/minecraft/raids.dat")), "raid data is discarded");
        assertFalse(Files.exists(out.resolve("session.lock")));
        Map<String, Object> json = Json.object(Json.parse(Files.readString(report)), "report");
        assertEquals(5023, json.get("dataVersion"));
        assertEquals("ours", json.get("newer"));
        assertEquals(List.of(), json.get("entries"));

        // Same inputs, same output: a second run writes the same decoded world.
        Path again = temp.resolve("again");
        assertEquals(0, run(FIXTURE, FIXTURE, FIXTURE, "ours", again).exit());
        assertEquals(-1, Files.mismatch(out.resolve(DIM + "region/r.1.1.mca"), again.resolve(DIM + "region/r.1.1.mca")));
    }

    @Test
    void twoPlayedCopiesMergeIntoOneWorldWithAReport() throws Exception {
        Path ours = copyOfFixture("ours");
        Path theirs = copyOfFixture("theirs");
        // Chunk (47,47): both build, once on the same block. Chunk (48,47): only ours builds.
        edit(ours, 47, 47, c -> set(set(c, 760, 250, 760, "minecraft:stone_bricks"), 753, 260, 753, "minecraft:glass"));
        edit(theirs, 47, 47, c -> set(set(c, 760, 250, 760, "minecraft:oak_planks"), 765, 262, 765, "minecraft:dirt"));
        edit(ours, 48, 47, c -> set(c, 770, 250, 760, "minecraft:gold_block"));
        Path out = temp.resolve("out");
        Path report = temp.resolve("nested/report.json");
        Run run = run(FIXTURE, ours, theirs, "theirs", out, "--report", report.toString(), "--compression", "lz4");
        assertEquals(1, run.exit(), run.err());
        assertTrue(run.out().contains("1 conflicts"), run.out());

        ChunkData merged = chunk(out, 47, 47);
        assertEquals(BlockState.of("minecraft:oak_planks"), merged.blockState(760, 250, 760), "theirs is newer");
        assertEquals(BlockState.of("minecraft:glass"), merged.blockState(753, 260, 753));
        assertEquals(BlockState.of("minecraft:dirt"), merged.blockState(765, 262, 765));
        assertNull(merged.lightOn(), "a chunk mixing both sides is relit by the game");
        assertNull(merged.heightmaps());
        assertEquals(chunk(ours, 48, 47), chunk(out, 48, 47), "a chunk only ours changed is ours, light and all");
        assertEquals(Compression.LZ4, ((RegionSlot.Chunk) region(out, "region").slot(47, 47)).compression());
        assertEquals(((RegionSlot.Chunk) region(ours, "region").slot(47, 47)).timestamp(),
                ((RegionSlot.Chunk) region(out, "region").slot(47, 47)).timestamp());
        // The mixed chunk's POI entry is dropped so the game rebuilds it; the others stay.
        assertTrue(region(FIXTURE, "poi").slot(47, 47) instanceof RegionSlot.Chunk);
        assertTrue(region(out, "poi").slot(47, 47) instanceof RegionSlot.Empty);
        assertEquals(region(FIXTURE, "poi").usedSlots().size() - 1, region(out, "poi").usedSlots().size());

        Map<String, Object> json = Json.object(Json.parse(Files.readString(report)), "report");
        assertEquals(1, Json.object(json.get("summary"), "summary").get("conflict"));
        Map<String, Object> entry = Json.object(Json.array(json.get("entries"), "entries").get(0), "entry");
        assertEquals("block", entry.get("category"));
        assertEquals("minecraft:overworld", entry.get("dimension"));
        assertEquals(List.of(760, 250, 760), entry.get("pos"));
        assertEquals("minecraft:air", entry.get("base"));
        assertEquals("minecraft:stone_bricks", entry.get("ours"));
        assertEquals("minecraft:oak_planks", entry.get("theirs"));
        assertEquals("theirs", entry.get("winner"));
    }

    @Test
    void timersGoToTheNewerSideSilentlyAndForcedChunksMergeAsASet() throws Exception {
        Path ours = copyOfFixture("ours");
        Path theirs = copyOfFixture("theirs");
        // The wandering trader's timer runs on both sides by itself.
        for (Path world : List.of(ours, theirs)) {
            NbtIo.writeGzipFile(world.resolve("data/minecraft/wandering_trader.dat"), NamedTag.of(CompoundTag.builder()
                    .put("DataVersion", new IntTag(5023))
                    .put("data", CompoundTag.of("spawn_delay", new IntTag(world == ours ? 900 : 300))).build()),
                    NbtIo.KeyOrder.STORED);
        }
        // So do the game's random number sequences, whenever loot is rolled.
        for (Path world : List.of(ours, theirs)) {
            NbtIo.writeGzipFile(world.resolve("data/minecraft/random_sequences.dat"), NamedTag.of(CompoundTag.builder()
                    .put("DataVersion", new IntTag(5023))
                    .put("data", CompoundTag.of("salt", new IntTag(world == ours ? 11 : 22))).build()), NbtIo.KeyOrder.STORED);
        }
        // Ours forces one more chunk; theirs forces another and stops forcing chunk (46, 46).
        String tickets = DIM + "data/minecraft/chunk_tickets.dat";
        for (Path world : List.of(ours, theirs)) {
            NamedTag root = NbtIo.readGzipFile(world.resolve(tickets));
            CompoundTag data = (CompoundTag) ((CompoundTag) root.tag()).get("data");
            List<mycelium.nbt.Tag> list = new java.util.ArrayList<>(
                    ((mycelium.nbt.ListTag) data.get("tickets")).elements());
            if (world == theirs) {
                list.remove(ticket(46, 46));
            }
            list.add(ticket(world == ours ? 100 : 200, 7));
            NbtIo.writeGzipFile(world.resolve(tickets), new NamedTag("", ((CompoundTag) root.tag()).with("data",
                    data.with("tickets", mycelium.nbt.ListTag.of(list)))), NbtIo.KeyOrder.STORED);
        }
        Path out = temp.resolve("out");
        Run run = run(FIXTURE, ours, theirs, "theirs", out);
        assertEquals(0, run.exit(), run.out() + run.err());
        assertTrue(run.out().contains("0 conflicts"), run.out());
        for (String timer : List.of("wandering_trader.dat", "random_sequences.dat")) {
            assertEquals(NbtIo.readGzipFile(theirs.resolve("data/minecraft/" + timer)),
                    NbtIo.readGzipFile(out.resolve("data/minecraft/" + timer)), timer);
        }
        List<mycelium.nbt.Tag> merged = ((mycelium.nbt.ListTag)
                ((CompoundTag) ((CompoundTag) NbtIo.readGzipFile(out.resolve(tickets)).tag()).get("data")).get("tickets")).elements();
        assertEquals(13, merged.size(), "twelve, minus the one theirs dropped, plus one from each side");
        assertTrue(merged.contains(ticket(100, 7)) && merged.contains(ticket(200, 7)));
        assertFalse(merged.contains(ticket(46, 46)));
    }

    private static CompoundTag ticket(int x, int z) {
        return CompoundTag.builder().put("chunk_pos", new mycelium.nbt.IntArrayTag(new int[] {x, z}))
                .put("level", new IntTag(31)).put("type", new mycelium.nbt.StringTag("minecraft:forced")).build();
    }

    @Test
    void chunksWithoutAnAncestorAreCountedAndTheToolSaysSo() throws Exception {
        Path base = copyOfFixture("base");
        Files.delete(base.resolve(DIM + "region/r.1.1.mca"));
        // Without the base's region, a chunk that is the same on both sides needs no ancestor.
        Run same = run(base, FIXTURE, FIXTURE, "ours", temp.resolve("same"));
        assertEquals(0, same.exit(), same.err());
        assertFalse(same.out().contains("note:"), same.out());

        Path ours = copyOfFixture("ours");
        Path theirs = copyOfFixture("theirs");
        edit(ours, 47, 47, c -> set(c, 753, 260, 753, "minecraft:glass"));
        edit(theirs, 47, 47, c -> set(c, 765, 262, 765, "minecraft:dirt"));
        Path report = temp.resolve("report.json");
        Run run = run(base, ours, theirs, "ours", temp.resolve("out"), "--report", report.toString());
        assertEquals(1, run.exit(), run.err());
        assertTrue(run.out().contains("note: 1 chunks were generated by both sides") && run.out().contains("--generated-base"), run.out());
        assertEquals(1, Json.object(Json.parse(Files.readString(report)), "report").get("chunksWithoutAncestor"));
        assertEquals(BlockState.of("minecraft:glass"), chunk(temp.resolve("out"), 47, 47).blockState(753, 260, 753));
        assertEquals(BlockState.of("minecraft:air"), chunk(temp.resolve("out"), 47, 47).blockState(765, 262, 765),
                "without an ancestor the newer side's block is taken, and theirs is lost");
        // With the chunks in a generated base both builds are kept and there is nothing to say.
        Run quiet = run(base, ours, theirs, "ours", temp.resolve("out2"), "--generated-base", FIXTURE.toString());
        assertEquals(0, quiet.exit(), quiet.out() + quiet.err());
        assertFalse(quiet.out().contains("note:"), quiet.out());
        assertEquals(BlockState.of("minecraft:dirt"), chunk(temp.resolve("out2"), 47, 47).blockState(765, 262, 765));
    }

    @Test
    void aCorruptChunkIsRefusedAndTheReadableSideIsWritten() throws Exception {
        Path ours = copyOfFixture("ours");
        Path theirs = copyOfFixture("theirs");
        edit(theirs, 47, 47, c -> set(c, 760, 250, 760, "minecraft:oak_planks"));
        // Damage ours' copy of the chunk: its slot now claims a length of zero.
        Path file = ours.resolve(DIM + "region/r.1.1.mca");
        byte[] bytes = Files.readAllBytes(file);
        int sector = ByteBuffer.wrap(bytes).getInt(RegionPos.slotIndex(47, 47) * 4) >>> 8;
        ByteBuffer.wrap(bytes).putInt(sector * 4096, 0);
        Files.write(file, bytes);

        Path out = temp.resolve("out");
        Path report = temp.resolve("report.json");
        Run run = run(FIXTURE, ours, theirs, "ours", out, "--report", report.toString());
        assertEquals(1, run.exit(), run.err());
        assertTrue(run.out().contains("1 refused"), run.out());
        assertEquals(chunk(theirs, 47, 47), chunk(out, 47, 47), "ours is newer but unreadable, so theirs is written");
        assertEquals(870, region(out, "region").usedSlots().size(), "no chunk is dropped");
        Map<String, Object> entry = Json.object(Json.array(Json.object(Json.parse(Files.readString(report)), "report")
                .get("entries"), "entries").get(0), "entry");
        assertEquals("refused", entry.get("severity"));
        assertEquals("theirs", entry.get("winner"));
        assertTrue(((String) entry.get("detail")).contains("declared length is 0"));
    }

    @Test
    void inputsThatCannotBeMergedAtAllAreRefusedWithExitCodeTwo() throws Exception {
        Path out = temp.resolve("out");
        Path older = copyOfFixture("older");
        NamedTag level = NbtIo.readGzipFile(older.resolve("level.dat"));
        CompoundTag data = (CompoundTag) ((CompoundTag) level.tag()).get("Data");
        NbtIo.writeGzipFile(older.resolve("level.dat"), new NamedTag("", ((CompoundTag) level.tag())
                .with("Data", data.with("DataVersion", new IntTag(4671)))), NbtIo.KeyOrder.STORED);
        Run version = run(FIXTURE, older, FIXTURE, "ours", out);
        assertEquals(2, version.exit());
        assertTrue(version.err().contains("4671") && version.err().contains("level.dat"), version.err());
        assertFalse(Files.exists(out), "nothing is written when the merge is refused");

        Files.createDirectories(out);
        Files.writeString(out.resolve("something"), "in the way");
        assertEquals(2, run(FIXTURE, FIXTURE, FIXTURE, "ours", out).exit(), "the output folder must be empty");
        assertEquals(2, run(FIXTURE, FIXTURE, temp.resolve("missing"), "ours", temp.resolve("o2")).exit());
        assertEquals(2, run(FIXTURE, FIXTURE, FIXTURE, "sideways", temp.resolve("o3")).exit());
        assertEquals(2, run(FIXTURE, FIXTURE, FIXTURE, "ours", temp.resolve("o4"), "--dimension", "minecraft:the_moon").exit());
        assertEquals(2, run(FIXTURE, FIXTURE, FIXTURE, "ours", temp.resolve("o5"), "--frobnicate", "yes").exit());
        assertEquals(0, Main.run(new String[] {"--help"}, new PrintStream(new ByteArrayOutputStream()), System.err));
    }


    @ParameterizedTest
    @EnumSource(DimensionMerger.Relight.class)
    void dividingTheWorkDifferentlyGivesTheSameWorldAndReport(DimensionMerger.Relight relight) throws Exception {
        Path ours = copyOfFixture("ours");
        Path theirs = copyOfFixture("theirs");
        // Both build in chunk (47,47), once on the same block; each builds alone in another chunk.
        edit(ours, 47, 47, c -> set(set(c, 760, 250, 760, "minecraft:stone_bricks"), 753, 260, 753, "minecraft:glass"));
        edit(theirs, 47, 47, c -> set(set(c, 760, 250, 760, "minecraft:oak_planks"), 765, 262, 765, "minecraft:dirt"));
        edit(ours, 51, 49, c -> set(c, 820, 250, 790, "minecraft:gold_block"));
        edit(theirs, 44, 45, c -> set(c, 710, 250, 725, "minecraft:iron_block"));
        // Ours lays a bed across the border between chunks (47,46) and (48,46). Theirs puts stone where its head is.
        edit(ours, 47, 46, c -> set(c, 767, 250, 740, "minecraft:red_bed[facing=east,occupied=false,part=foot]"));
        edit(ours, 48, 46, c -> set(c, 768, 250, 740, "minecraft:red_bed[facing=east,occupied=false,part=head]"));
        edit(theirs, 48, 46, c -> set(c, 768, 250, 740, "minecraft:stone"));

        Map<String, WorldMerger.Tuning> ways = new java.util.LinkedHashMap<>();
        ways.put("reference", new WorldMerger.Tuning(1, 32, false));   // every chunk decoded, merged and written again
        ways.put("default", WorldMerger.Tuning.auto());
        ways.put("small", new WorldMerger.Tuning(3, 4, true));         // chunks 47 and 48 are in different squares
        String expected = null;
        for (Map.Entry<String, WorldMerger.Tuning> way : ways.entrySet()) {
            MergeReport report = WorldMerger.merge(new WorldMerger.Options(FIXTURE, ours, theirs, Side.THEIRS,
                    temp.resolve(way.getKey()), null, null, relight, Compression.ZLIB, TestChunks.RULES), way.getValue());
            if (expected == null) {
                expected = report.toJson();
                assertTrue(report.entries().stream().anyMatch(entry -> entry.category() == Category.UNIT), "the bed was repaired");
            }
            assertEquals(expected, report.toJson(), way.getKey());
            assertEquals(List.of(), CompareWorlds.differences(temp.resolve("reference"), temp.resolve(way.getKey())), way.getKey());
        }
        ChunkData head = chunk(temp.resolve("default"), 48, 46);
        ChunkData foot = chunk(temp.resolve("default"), 47, 46);
        assertEquals(BlockState.of("minecraft:stone"), head.blockState(768, 250, 740), "theirs is newer");
        assertEquals(chunk(FIXTURE, 47, 46).blockState(767, 250, 740), foot.blockState(767, 250, 740),
                "so the whole bed is theirs: none, and the block that was there stays");
        // What only one side touched, well away from the other side's changes, is that side's chunk byte for
        // byte, unless the relight mode strips it.
        if (relight == DimensionMerger.Relight.CHANGED) {
            assertTrue(((RawSlot.Stored) region(temp.resolve("default"), "region").raw(RegionPos.slotIndex(51, 49)))
                    .sameBytes((RawSlot.Stored) region(ours, "region").raw(RegionPos.slotIndex(51, 49))));
        }
    }

    /** Merges with the default division and with the reference, and checks that they agree. */
    private Path mergedBothWays(Path base, Path ours, Path theirs, Side newer, List<MergeReport> reports) throws Exception {
        Path reference = Files.createTempDirectory(temp, "reference");
        Path merged = Files.createTempDirectory(temp, "default");
        MergeReport expected = WorldMerger.merge(new WorldMerger.Options(base, ours, theirs, newer, reference, null, null,
                DimensionMerger.Relight.CHANGED, Compression.ZLIB, TestChunks.RULES), new WorldMerger.Tuning(1, 32, false));
        MergeReport report = WorldMerger.merge(new WorldMerger.Options(base, ours, theirs, newer, merged, null, null,
                DimensionMerger.Relight.CHANGED, Compression.ZLIB, TestChunks.RULES), WorldMerger.Tuning.auto());
        assertEquals(expected.toJson(), report.toJson());
        assertEquals(List.of(), CompareWorlds.differences(reference, merged));
        reports.add(report);
        return merged;
    }

    @Test
    void changesOfTheTwoSidesInNeighbouringChunksAreMergedNotCopied() throws Exception {
        // Ours puts a fence in the last column of chunk (47,46). Theirs puts stone in the first column of (48,46),
        // right beside it. Each chunk was changed by one side only, yet neither can be copied as stored: the
        // fence now stands next to a block it never stood next to, and the game must be told to redo it.
        Path ours = copyOfFixture("ours");
        Path theirs = copyOfFixture("theirs");
        edit(ours, 47, 46, c -> set(c, 767, 250, 740, "minecraft:oak_fence"));
        edit(theirs, 48, 46, c -> set(c, 768, 250, 740, "minecraft:stone"));
        Path merged = mergedBothWays(FIXTURE, ours, theirs, Side.THEIRS, new java.util.ArrayList<>());
        assertEquals(BlockState.of("minecraft:oak_fence"), chunk(merged, 47, 46).blockState(767, 250, 740));
        assertEquals(BlockState.of("minecraft:stone"), chunk(merged, 48, 46).blockState(768, 250, 740));
        assertFalse(chunk(merged, 47, 46).postProcessing().equals(chunk(ours, 47, 46).postProcessing()),
                "the fence is listed for a refresh, so the chunk is not ours' as stored");
        assertFalse(chunk(merged, 48, 46).postProcessing().equals(chunk(theirs, 48, 46).postProcessing()));
    }

    @Test
    void aChunkBothSidesChangedAlikeStillMeetsAChunkOneSideChanged() throws Exception {
        // The base has the head of a bed without a foot, in chunk (48,46). Both sides put the foot next to it,
        // in chunk (47,46), in the very same way. Theirs then removes the head. With ours as the newer side,
        // merging everything at once takes the bed from ours, head included. Copying both chunks as stored,
        // because no chunk was changed differently by the two sides, would leave the foot alone.
        String foot = "minecraft:red_bed[facing=east,occupied=false,part=foot]";
        String head = "minecraft:red_bed[facing=east,occupied=false,part=head]";
        Path base = copyOfFixture("base");
        edit(base, 48, 46, c -> set(c, 768, 250, 740, head));
        Path ours = temp.resolve("ours");
        Path theirs = temp.resolve("theirs");
        for (Path side : List.of(ours, theirs)) {
            try (Stream<Path> walk = Files.walk(base)) {
                for (Path file : walk.filter(Files::isRegularFile).toList()) {
                    Files.createDirectories(side.resolve(base.relativize(file)).getParent());
                    Files.copy(file, side.resolve(base.relativize(file)));
                }
            }
            edit(side, 47, 46, c -> set(c, 767, 250, 740, foot));
        }
        edit(theirs, 48, 46, c -> set(c, 768, 250, 740, "minecraft:air"));
        assertTrue(((RawSlot.Stored) region(ours, "region").raw(RegionPos.slotIndex(47, 46)))
                .sameBytes((RawSlot.Stored) region(theirs, "region").raw(RegionPos.slotIndex(47, 46))), "changed alike, to the byte");

        List<MergeReport> reports = new java.util.ArrayList<>();
        Path merged = mergedBothWays(base, ours, theirs, Side.OURS, reports);
        assertEquals(BlockState.parse(head), chunk(merged, 48, 46).blockState(768, 250, 740), "the bed is whole");
        assertTrue(reports.get(0).entries().stream().anyMatch(entry -> entry.category() == Category.UNIT));
    }

    @Test
    void worldGenSettingsThatDifferRefuseTheMerge() throws Exception {
        Path theirs = copyOfFixture("theirs");
        editData(theirs, "data/minecraft/world_gen_settings.dat", data -> data.with("seed", new mycelium.nbt.LongTag(7)));
        Run run = run(FIXTURE, FIXTURE, theirs, "ours", temp.resolve("out"));
        assertEquals(2, run.exit());
        assertTrue(run.err().contains("world-gen settings differ"), run.err());
        assertFalse(Files.exists(temp.resolve("out")), "nothing is written when the merge is refused");
    }

    @Test
    void aWorldThatIsOpenInTheGameIsRefused() throws Exception {
        Path theirs = copyOfFixture("theirs");
        Files.writeString(theirs.resolve("session.lock"), "\u2603");
        // The game holds this lock for as long as it has the world open.
        try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(theirs.resolve("session.lock"),
                java.nio.file.StandardOpenOption.WRITE)) {
            assertTrue(channel.lock().isValid());
            Run run = run(FIXTURE, FIXTURE, theirs, "ours", temp.resolve("out"));
            assertEquals(2, run.exit());
            assertTrue(run.err().contains("is open in the game"), run.err());
            assertFalse(Files.exists(temp.resolve("out")));
        }
        assertEquals(0, run(FIXTURE, FIXTURE, theirs, "ours", temp.resolve("out")).exit(), "once it is closed, the merge runs");
        assertFalse(Files.exists(temp.resolve("out").resolve("session.lock")));
    }

    @Test
    void aFailureAfterWritingBeganExitsWithThreeAndSaysTheOutputIsIncomplete() throws Exception {
        // The report cannot be written where a folder is in the way. The world is merged by then.
        Path blocked = Files.createDirectories(temp.resolve("report.json"));
        Run report = run(FIXTURE, FIXTURE, FIXTURE, "ours", temp.resolve("out"), "--report", blocked.toString());
        assertEquals(3, report.exit());
        assertTrue(report.err().contains("the report could not be"), report.err());

        // A file of an input that cannot be read stops the merge half way.
        Path theirs = copyOfFixture("theirs");
        Path unreadable = theirs.resolve("data/minecraft/scoreboard.dat");
        org.junit.jupiter.api.Assumptions.assumeTrue(unreadable.getFileSystem().supportedFileAttributeViews().contains("posix"));
        Files.setPosixFilePermissions(unreadable, java.util.Set.of());
        try {
            Run run = run(FIXTURE, FIXTURE, theirs, "ours", temp.resolve("out2"));
            assertEquals(3, run.exit(), run.err());
            assertTrue(run.err().contains("the output folder is incomplete"), run.err());
        } finally {
            Files.setPosixFilePermissions(unreadable, java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"));
        }
    }

    @Test
    void everyDimensionIsMergedAndOneCanBeSelected() throws Exception {
        String nether = "dimensions/minecraft/the_nether/";
        String nested = "dimensions/mymod/worlds/mining/";      // the id mymod:worlds/mining
        Path base = copyOfFixture("base");
        addDimension(base, nether);
        addDimension(base, nested);
        Path ours = copyOfFixture("ours");
        Path theirs = copyOfFixture("theirs");
        for (Path side : List.of(ours, theirs)) {
            addDimension(side, nether);
            addDimension(side, nested);
        }
        for (String dimension : List.of(DIM, nether, nested)) {
            edit(ours, dimension, 47, 47, c -> set(c, 753, 260, 753, "minecraft:glass"));
            edit(theirs, dimension, 47, 47, c -> set(c, 765, 262, 765, "minecraft:dirt"));
        }

        Path all = temp.resolve("all");
        assertEquals(0, run(base, ours, theirs, "theirs", all).exit());
        for (String dimension : List.of(DIM, nether, nested)) {
            ChunkData merged = chunk(all, dimension, 47, 47);
            assertEquals(BlockState.of("minecraft:glass"), merged.blockState(753, 260, 753), dimension);
            assertEquals(BlockState.of("minecraft:dirt"), merged.blockState(765, 262, 765), dimension);
        }

        // One dimension selected: it is merged, and the others are the newer side's, file for file.
        Path one = temp.resolve("one");
        assertEquals(0, run(base, ours, theirs, "theirs", one, "--dimension", "mymod:worlds/mining").exit());
        assertEquals(BlockState.of("minecraft:glass"), chunk(one, nested, 47, 47).blockState(753, 260, 753));
        assertEquals(BlockState.of("minecraft:dirt"), chunk(one, nested, 47, 47).blockState(765, 262, 765));
        for (String dimension : List.of(DIM, nether)) {
            for (String file : List.of("region/r.1.1.mca", "entities/r.1.1.mca", "poi/r.1.1.mca", "data/minecraft/chunk_tickets.dat",
                    "data/minecraft/raids.dat")) {
                assertEquals(-1, Files.mismatch(theirs.resolve(dimension + file), one.resolve(dimension + file)), dimension + file);
            }
        }
    }

    @Test
    void gameRulesClocksAndLevelFieldsMergeOneByOne() throws Exception {
        Path ours = copyOfFixture("ours");
        Path theirs = copyOfFixture("theirs");
        String rules = "data/minecraft/game_rules.dat";
        editData(ours, rules, data -> data.with("minecraft:tnt_explodes", new mycelium.nbt.ByteTag((byte) 0))
                .with("minecraft:spread_vines", new mycelium.nbt.ByteTag((byte) 0)));
        editData(theirs, rules, data -> data.with("minecraft:block_drops", new mycelium.nbt.ByteTag((byte) 0))
                .with("minecraft:spread_vines", new mycelium.nbt.ByteTag((byte) 0)));
        String clocks = "data/minecraft/world_clocks.dat";
        editData(ours, clocks, data -> data.with("minecraft:overworld", CompoundTag.of("total_ticks", new mycelium.nbt.LongTag(900))));
        editData(theirs, clocks, data -> data.with("minecraft:overworld", CompoundTag.of("total_ticks", new mycelium.nbt.LongTag(500))));
        editData(ours, "level.dat", data -> data.with("Time", new mycelium.nbt.LongTag(9000))
                .with("GameType", new IntTag(1)).with("LevelName", new mycelium.nbt.StringTag("ours' name")));
        editData(theirs, "level.dat", data -> data.with("Time", new mycelium.nbt.LongTag(5000))
                .with("allowCommands", new mycelium.nbt.ByteTag((byte) 1)).with("LevelName", new mycelium.nbt.StringTag("theirs' name")));

        Path out = temp.resolve("out");
        Path report = temp.resolve("report.json");
        Run run = run(FIXTURE, ours, theirs, "theirs", out, "--report", report.toString());
        assertEquals(1, run.exit(), run.err());
        CompoundTag merged = data(out, rules);
        assertEquals(new mycelium.nbt.ByteTag((byte) 0), merged.get("minecraft:tnt_explodes"), "the rule ours changed");
        assertEquals(new mycelium.nbt.ByteTag((byte) 0), merged.get("minecraft:block_drops"), "the rule theirs changed");
        assertEquals(new mycelium.nbt.ByteTag((byte) 0), merged.get("minecraft:spread_vines"));
        assertEquals(data(FIXTURE, rules).get("minecraft:spawn_monsters"), merged.get("minecraft:spawn_monsters"));
        assertEquals(new mycelium.nbt.LongTag(900), ((CompoundTag) data(out, clocks).get("minecraft:overworld")).get("total_ticks"),
                "a clock is at the later of the two times");
        CompoundTag level = data(out, "level.dat");
        assertEquals(new mycelium.nbt.LongTag(9000), level.get("Time"));
        assertEquals(new IntTag(1), level.get("GameType"), "a field only ours changed");
        assertEquals(new mycelium.nbt.ByteTag((byte) 1), level.get("allowCommands"), "a field only theirs changed");
        assertEquals(new mycelium.nbt.StringTag("theirs' name"), level.get("LevelName"), "both changed it: the newer side's");
        List<Object> entries = Json.array(Json.object(Json.parse(Files.readString(report)), "report").get("entries"), "entries");
        assertEquals(1, entries.size(), "the name is the one conflict; game time and clocks are never one");
        assertEquals("level", Json.object(entries.get(0), "entry").get("category"));
        assertEquals("level.dat LevelName", Json.object(entries.get(0), "entry").get("detail"));
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"probe-zlib", "probe-lz4", "probe-none"})
    void everyFixtureWorldMergesWithItselfAndWithAPlayedCopy(String name) throws Exception {
        // The LZ4 world and the uncompressed one, which holds a chunk too large for its region file, go
        // through the whole tool too, not only through the region reader.
        Path world = Fixtures.world(name);
        Path same = temp.resolve("same");
        assertEquals(0, run(world, world, world, "ours", same).exit());
        assertEquals(List.of(), CompareWorlds.differences(world, same).stream()
                .filter(line -> !line.contains("raids.dat")).toList(), "raid data is discarded; everything else is as it was");

        // Mixed compression: ours is the same world written again as zlib, with every chunk's bytes different.
        Path ours = temp.resolve("ours");
        mycelium.testing.RewriteWorld.main(new String[] {world.toString(), ours.toString(), "ZLIB"});
        Path mixed = temp.resolve("mixed");
        assertEquals(0, run(world, ours, world, "ours", mixed, "--compression", "lz4").exit());
        assertEquals(List.of(), CompareWorlds.differences(same, mixed).stream()
                .filter(line -> !line.contains("timestamps")).toList());
    }

    @Test
    void theWorkIsDividedToFitTheHeap() {
        long megabyte = 1 << 20;
        assertEquals(new WorldMerger.Tuning(1, 4, true), WorldMerger.Tuning.fitting(32 * megabyte, 8), "the smallest division, at least");
        for (long heap : new long[] {256, 1024, 2048, 4096, 9216, 32768}) {
            WorldMerger.Tuning tuning = WorldMerger.Tuning.fitting(heap * megabyte, 12);
            assertTrue(tuning.threads() >= 1 && tuning.threads() <= 12, heap + " MB: " + tuning);
            assertTrue(tuning.copyUnchanged());
        }
        assertEquals(1, WorldMerger.Tuning.fitting(32768 * megabyte, 1).threads());
        assertTrue(WorldMerger.Tuning.fitting(32768 * megabyte, 12).threads() > WorldMerger.Tuning.fitting(512 * megabyte, 12).threads());
    }
}
