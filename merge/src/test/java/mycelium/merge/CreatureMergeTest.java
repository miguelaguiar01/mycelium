package mycelium.merge;

import static mycelium.testing.TestChunks.set;
import static mycelium.testing.TestChunks.setBlockEntity;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import mycelium.chunk.BlockPos;
import mycelium.chunk.BlockState;
import mycelium.chunk.ChunkCodec;
import mycelium.chunk.ChunkData;
import mycelium.chunk.ChunkPos;
import mycelium.chunk.DataVersions;
import mycelium.chunk.Entities;
import mycelium.chunk.EntityChunk;
import mycelium.chunk.EntityChunkCodec;
import mycelium.nbt.CompoundTag;
import mycelium.nbt.DoubleTag;
import mycelium.nbt.IntArrayTag;
import mycelium.nbt.IntTag;
import mycelium.nbt.ListTag;
import mycelium.nbt.NbtIo;
import mycelium.nbt.StringTag;
import mycelium.nbt.TagType;
import mycelium.region.Compression;
import mycelium.region.RawSlot;
import mycelium.region.RegionFile;
import mycelium.region.RegionPos;
import mycelium.region.RegionSlot;
import mycelium.region.RegionWriter;
import mycelium.report.Category;
import mycelium.report.MergeReport;
import mycelium.report.ReportEntry;
import mycelium.report.Severity;
import mycelium.testing.Fixtures;
import mycelium.testing.TestChunks;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The creature rules through the whole tool: what the block merge and the block files tell the
 * entity merge. The rules themselves are tested on the entity merger alone, with the eggs and
 * nests handed to it; here they come out of world folders.
 *
 * <p>The worlds are the fixture world with an egg, a nest and creatures added to chunk (47,47),
 * which has no entities of its own.
 */
class CreatureMergeTest {

    private static final String DIM = "dimensions/minecraft/overworld/";
    private static final Path FIXTURE = Fixtures.world("probe-zlib");
    private static final int CX = 47;
    private static final int CZ = 47;
    /** Three blocks of chunk (47,47), well apart. */
    private static final BlockPos EGG = new BlockPos(754, 250, 754);
    private static final BlockPos SPAWN = new BlockPos(764, 250, 754);
    private static final BlockPos NEST = new BlockPos(760, 250, 764);

    @TempDir
    Path temp;

    private Path copy(Path world, String name) throws IOException {
        Path target = temp.resolve(name);
        try (Stream<Path> walk = Files.walk(world)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                Path copy = target.resolve(world.relativize(file));
                Files.createDirectories(copy.getParent());
                Files.copy(file, copy);
            }
        }
        return target;
    }

    /** Changes the blocks of chunk (47,47) and leaves every other chunk of the region file as it is stored. */
    private static void blocks(Path world, UnaryOperator<ChunkData> change) throws Exception {
        RegionFile region = RegionFile.read(world.resolve(DIM + "region/r.1.1.mca"));
        SortedMap<Integer, RegionWriter.Record> chunks = new TreeMap<>();
        for (int slot : region.usedSlots()) {
            RawSlot.Stored stored = (RawSlot.Stored) region.raw(slot);
            if (slot == RegionPos.slotIndex(CX, CZ)) {
                RegionSlot.Chunk read = (RegionSlot.Chunk) region.slot(slot);
                chunks.put(slot, RegionWriter.Record.encode(read.timestamp() + 60,
                        ChunkCodec.encode(change.apply(ChunkCodec.decode(read.root()))), Compression.ZLIB, NbtIo.KeyOrder.STORED));
            } else {
                chunks.put(slot, RegionWriter.Record.copy(stored.timestamp(), stored, stored.compression()));
            }
        }
        RegionWriter.write(world.resolve(DIM + "region"), region.pos(), chunks);
    }

    /** Gives chunk (47,47) of a world these entities and leaves the other entity chunks as they are stored. */
    private static void entities(Path world, CompoundTag... entities) throws Exception {
        entities(world, CX, entities);
    }

    /** The same for the chunk at ({@code chunkX}, 47). */
    private static void entities(Path world, int chunkX, CompoundTag... entities) throws Exception {
        RegionFile region = RegionFile.read(world.resolve(DIM + "entities/r.1.1.mca"));
        SortedMap<Integer, RegionWriter.Record> chunks = new TreeMap<>();
        for (int slot : region.usedSlots()) {
            RawSlot.Stored stored = (RawSlot.Stored) region.raw(slot);
            chunks.put(slot, RegionWriter.Record.copy(stored.timestamp(), stored, stored.compression()));
        }
        chunks.put(RegionPos.slotIndex(chunkX, CZ), RegionWriter.Record.encode(1_000, EntityChunkCodec.encode(
                new EntityChunk(DataVersions.MC_26_3, new ChunkPos(chunkX, CZ), List.of(entities), CompoundTag.empty())),
                Compression.ZLIB, NbtIo.KeyOrder.SORTED));
        RegionWriter.write(world.resolve(DIM + "entities"), region.pos(), chunks);
    }

    private static UUID id(int n) {
        return new UUID(7, n);
    }

    /** An entity one block above a block of the chunk, a little to the east of it. */
    private static CompoundTag creature(int n, String kind, BlockPos near, double east) {
        return CompoundTag.builder().put("id", new StringTag(kind)).put("UUID", Entities.toTag(id(n)))
                .put("Pos", ListTag.of(List.of(new DoubleTag(near.x() + 0.5 + east), new DoubleTag(near.y() + 1), new DoubleTag(near.z() + 0.5))))
                .build();
    }

    private static CompoundTag bee(int n) {
        return creature(n, "minecraft:bee", NEST, n % 3).with("hive_pos", new IntArrayTag(new int[] {NEST.x(), NEST.y(), NEST.z()}));
    }

    /** A bee nest's block entity with this many bees inside, each having rested for {@code ticks}. */
    private static CompoundTag nest(int bees, int ticks) {
        List<CompoundTag> inside = new ArrayList<>();
        for (int n = 0; n < bees; n++) {
            inside.add(CompoundTag.builder().put("entity_data", CompoundTag.builder().put("id", new StringTag("minecraft:bee")).build())
                    .put("ticks_in_hive", new IntTag(ticks)).put("min_ticks_in_hive", new IntTag(600)).build());
        }
        return CompoundTag.builder().put("id", new StringTag("minecraft:beehive")).put("x", new IntTag(NEST.x()))
                .put("y", new IntTag(NEST.y())).put("z", new IntTag(NEST.z())).put("bees", ListTag.of(TagType.COMPOUND, inside)).build();
    }

    private static ChunkData withNest(ChunkData chunk, int bees, int ticks) {
        return setBlockEntity(set(chunk, NEST.x(), NEST.y(), NEST.z(), "minecraft:bee_nest"), NEST, nest(bees, ticks));
    }

    private MergeReport merge(Path base, Path ours, Path theirs, Side newer, String out) throws Exception {
        return WorldMerger.merge(new WorldMerger.Options(base, ours, theirs, newer, temp.resolve(out), null, null,
                DimensionMerger.Relight.CHANGED, Compression.ZLIB, TestChunks.RULES));
    }

    /** The numbers of the test's own creatures in the merged world, in order. */
    private List<Integer> creatures(String out) throws Exception {
        RegionFile region = RegionFile.read(temp.resolve(out).resolve(DIM + "entities/r.1.1.mca"));
        List<Integer> found = new ArrayList<>();
        for (int slot : region.usedSlots()) {
            for (CompoundTag entity : EntityChunkCodec.decode(((RegionSlot.Chunk) region.slot(slot)).root()).entities()) {
                UUID uuid = Entities.uuidOf(entity);
                if (uuid.getMostSignificantBits() == 7) {
                    found.add((int) uuid.getLeastSignificantBits());
                }
            }
        }
        found.sort(null);
        return found;
    }

    private ChunkData mergedChunk(String out) throws Exception {
        RegionFile region = RegionFile.read(temp.resolve(out).resolve(DIM + "region/r.1.1.mca"));
        return ChunkCodec.decode(((RegionSlot.Chunk) region.slot(CX, CZ)).root());
    }

    private static List<String> details(MergeReport report, Category category) {
        return report.entries().stream().filter(entry -> entry.category() == category).map(ReportEntry::detail).toList();
    }

    @Test
    void anEggBothSidesHatchedGivesItsCreaturesOnce() throws Exception {
        Path base = copy(FIXTURE, "base");
        blocks(base, chunk -> set(set(chunk, EGG.x(), EGG.y(), EGG.z(), "minecraft:sniffer_egg"),
                SPAWN.x(), SPAWN.y(), SPAWN.z(), "minecraft:frogspawn"));
        // Both sides hatched the sniffer egg. Only ours hatched the frogspawn.
        Path ours = copy(base, "ours");
        blocks(ours, chunk -> set(set(chunk, EGG.x(), EGG.y(), EGG.z(), "minecraft:air"), SPAWN.x(), SPAWN.y(), SPAWN.z(), "minecraft:air"));
        entities(ours, creature(1, "minecraft:sniffer", EGG, 1), creature(2, "minecraft:tadpole", SPAWN, 0), creature(3, "minecraft:tadpole", SPAWN, 1));
        Path theirs = copy(base, "theirs");
        blocks(theirs, chunk -> set(chunk, EGG.x(), EGG.y(), EGG.z(), "minecraft:air"));
        entities(theirs, creature(11, "minecraft:sniffer", EGG, 2));

        MergeReport report = merge(base, ours, theirs, Side.THEIRS, "newer-theirs");
        assertEquals(List.of(2, 3, 11), creatures("newer-theirs"), "theirs' sniffer, and ours' tadpoles, which have no twins");
        assertEquals(List.of(SectionMerge.HATCHED), details(report, Category.BLOCK), "the block merge says which egg is gone on both sides");
        assertEquals(EGG, report.entries().stream().filter(entry -> entry.category() == Category.BLOCK).findFirst().orElseThrow().pos());
        assertEquals(List.of("1 minecraft:sniffer of ours left out: minecraft:sniffer_egg is gone on both sides, and theirs has what came of it"),
                details(report, Category.ENTITY));
        assertEquals(BlockState.of("minecraft:air"), mergedChunk("newer-theirs").blockState(SPAWN.x(), SPAWN.y(), SPAWN.z()));
        assertTrue(report.entries().stream().allMatch(entry -> entry.severity() == Severity.RESOLVED));

        merge(base, ours, theirs, Side.OURS, "newer-ours");
        assertEquals(List.of(1, 2, 3), creatures("newer-ours"));

        // Theirs hatches the spawn as well, three tadpoles to ours' two: three stay, theirs being newer. Two
        // worlds that were played never hold a chunk byte for byte alike, if only for its time of saving;
        // here a block of glass stands for that. Chunks that are alike are copied and not looked into.
        blocks(theirs, chunk -> set(set(chunk, SPAWN.x(), SPAWN.y(), SPAWN.z(), "minecraft:air"), 753, 255, 753, "minecraft:glass"));
        entities(theirs, creature(11, "minecraft:sniffer", EGG, 2), creature(12, "minecraft:tadpole", SPAWN, 0),
                creature(13, "minecraft:tadpole", SPAWN, 1), creature(14, "minecraft:frog", SPAWN, 2));
        MergeReport both = merge(base, ours, theirs, Side.THEIRS, "both-spawned");
        assertEquals(List.of(11, 12, 13, 14), creatures("both-spawned"));
        assertEquals(2, details(both, Category.BLOCK).size());
        assertTrue(details(both, Category.ENTITY).contains(
                "2 minecraft:tadpole of ours left out: minecraft:frogspawn is gone on both sides, and theirs has what came of it"));
    }

    @Test
    void aTurtleEggBlockPairsOffAsManyTurtlesAsItHeldEggs() throws Exception {
        // One egg in the block, hatched on both sides. Each side also has a second young turtle next to it,
        // from an egg laid after the worlds parted. Only the one that hatched on both sides has a twin.
        Path base = copy(FIXTURE, "base");
        blocks(base, chunk -> set(chunk, EGG.x(), EGG.y(), EGG.z(), "minecraft:turtle_egg"));
        Path ours = copy(base, "ours");
        blocks(ours, chunk -> set(chunk, EGG.x(), EGG.y(), EGG.z(), "minecraft:air"));
        entities(ours, creature(1, "minecraft:turtle", EGG, 0), creature(2, "minecraft:turtle", EGG, 3));
        Path theirs = copy(base, "theirs");
        blocks(theirs, chunk -> set(set(chunk, EGG.x(), EGG.y(), EGG.z(), "minecraft:air"), 753, 255, 753, "minecraft:glass"));
        entities(theirs, creature(11, "minecraft:turtle", EGG, 1), creature(12, "minecraft:turtle", EGG, 4));

        merge(base, ours, theirs, Side.THEIRS, "one-egg");
        assertEquals(List.of(2, 11, 12), creatures("one-egg"), "ours' nearest turtle is the twin; its other one stays");
    }

    @Test
    void aCreatureThatTurnedIntoAnotherIsThereOnce() throws Exception {
        Path base = copy(FIXTURE, "base");
        entities(base, creature(1, "minecraft:tadpole", EGG, 0), creature(2, "minecraft:tadpole", SPAWN, 0),
                creature(3, "minecraft:zombie_villager", NEST, 0));
        // The first tadpole is a frog on both sides. The second is a frog on theirs and still a tadpole on
        // ours, where it swam on. The zombie villager is cured on both sides.
        Path ours = copy(base, "ours");
        entities(ours, creature(21, "minecraft:frog", EGG, 1), creature(2, "minecraft:tadpole", SPAWN, 2),
                creature(23, "minecraft:villager", NEST, 1));
        Path theirs = copy(base, "theirs");
        entities(theirs, creature(31, "minecraft:frog", EGG, 2), creature(32, "minecraft:frog", SPAWN, 1),
                creature(33, "minecraft:villager", NEST, 2));

        MergeReport report = merge(base, ours, theirs, Side.OURS, "newer-ours");
        assertEquals(List.of(21, 23, 32), creatures("newer-ours"),
                "ours' frog and villager, and theirs' frog of the tadpole that ours still had as a tadpole");
        List<String> said = details(report, Category.ENTITY);
        assertEquals(3, said.size(), said.toString());
        assertTrue(said.contains(id(2) + ": removed by theirs, only loaded by ours"), said.toString());
        assertTrue(said.contains("1 minecraft:frog of theirs left out: minecraft:tadpole " + id(1)
                + " is gone on both sides, and ours has what came of it"), said.toString());
        assertTrue(said.contains("1 minecraft:villager of theirs left out: minecraft:zombie_villager " + id(3)
                + " is gone on both sides, and ours has what came of it"), said.toString());

        merge(base, ours, theirs, Side.THEIRS, "newer-theirs");
        assertEquals(List.of(31, 32, 33), creatures("newer-theirs"));
    }

    @Test
    void whatBothSidesNoLongerHaveInAChunkTheyChangedAlikeIsStillKnown() throws Exception {
        // A tadpole and a cow, alone in their chunk. On both sides the tadpole became a frog, which hopped into
        // the next chunk. Ours led the cow there too; theirs killed it. The chunk they were in is empty on both
        // sides, byte for byte alike, so only the base says that they ever were.
        Path base = copy(FIXTURE, "base");
        entities(base, creature(1, "minecraft:tadpole", EGG, 0), creature(2, "minecraft:cow", EGG, 2));
        BlockPos next = new BlockPos(748, 250, 754);
        Path ours = copy(base, "ours");
        entities(ours);
        entities(ours, CX - 1, creature(21, "minecraft:frog", next, 0), creature(2, "minecraft:cow", next, 1));
        Path theirs = copy(base, "theirs");
        entities(theirs);
        entities(theirs, CX - 1, creature(31, "minecraft:frog", next, 0.5));

        MergeReport report = merge(base, ours, theirs, Side.THEIRS, "newer-theirs");
        assertEquals(List.of(31), creatures("newer-theirs"), "one frog, and the cow stays dead");
        List<String> said = details(report, Category.ENTITY);
        assertTrue(said.contains(id(2) + ": removed by theirs, only loaded by ours"), said.toString());
        assertTrue(said.contains("1 minecraft:frog of ours left out: minecraft:tadpole " + id(1)
                + " is gone on both sides, and theirs has what came of it"), said.toString());
        merge(base, ours, theirs, Side.OURS, "newer-ours");
        assertEquals(List.of(21), creatures("newer-ours"));
    }

    @Test
    void beesAreCountedAgainstTheNestTheMergedWorldHas() throws Exception {
        Path base = copy(FIXTURE, "base");
        blocks(base, chunk -> withNest(chunk, 3, 0));
        // On ours all three bees are out. On theirs they rest in the nest, which shows only in their time there.
        Path ours = copy(base, "ours");
        blocks(ours, chunk -> withNest(chunk, 0, 0));
        entities(ours, bee(1), bee(2), bee(3));
        Path theirs = copy(base, "theirs");
        blocks(theirs, chunk -> withNest(chunk, 3, 5_000));

        MergeReport report = merge(base, ours, theirs, Side.THEIRS, "full");
        assertEquals(List.of(), creatures("full"), "the nest is theirs and full");
        assertEquals(nest(3, 5_000), mergedChunk("full").blockEntities().get(NEST));
        assertEquals(List.of("3 minecraft:bee of ours left out: both sides changed their nest, and the nest of theirs has 3 of its own"),
                details(report, Category.ENTITY));
        assertEquals(List.of(), details(report, Category.BLOCK_ENTITY), "bees coming and going are no conflict");

        merge(base, ours, theirs, Side.OURS, "out");
        assertEquals(List.of(1, 2, 3), creatures("out"), "the nest is ours and empty, and its bees are in the air");
        assertEquals(nest(0, 0), mergedChunk("out").blockEntities().get(NEST));

        // Two of theirs' bees died in the nest: it has room for two of ours'.
        blocks(theirs, chunk -> withNest(chunk, 1, 5_000));
        merge(base, ours, theirs, Side.THEIRS, "room");
        assertEquals(List.of(1, 2), creatures("room"));

        // Theirs never had the nest loaded: it is ours, and the bees need no rule.
        MergeReport alone = merge(base, ours, base, Side.THEIRS, "alone");
        assertEquals(List.of(1, 2, 3), creatures("alone"));
        assertEquals(nest(0, 0), mergedChunk("alone").blockEntities().get(NEST));
        assertEquals(List.of(), details(alone, Category.ENTITY));
    }
}
