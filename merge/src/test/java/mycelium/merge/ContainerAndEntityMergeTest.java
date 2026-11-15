package mycelium.merge;

import static mycelium.testing.TestChunks.context;
import static mycelium.testing.TestChunks.set;
import static mycelium.testing.TestChunks.setBlockEntity;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import mycelium.chunk.BlockPos;
import mycelium.chunk.ChunkData;
import mycelium.chunk.Entities;
import mycelium.chunk.EntityIndex;
import mycelium.chunk.FlatEntity;
import mycelium.nbt.ByteTag;
import mycelium.nbt.CompoundTag;
import mycelium.nbt.DoubleTag;
import mycelium.nbt.IntArrayTag;
import mycelium.nbt.IntTag;
import mycelium.nbt.ListTag;
import mycelium.nbt.StringTag;
import mycelium.nbt.Tag;
import mycelium.report.Category;
import mycelium.report.ReportEntry;
import mycelium.report.Severity;
import mycelium.testing.TestChunks;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ContainerAndEntityMergeTest {

    private static final BlockPos POS = new BlockPos(5, 64, 5);

    private static CompoundTag item(int slot, String id, int count) {
        return CompoundTag.builder().put("Slot", new ByteTag((byte) slot)).put("id", new StringTag(id))
                .put("count", new IntTag(count)).build();
    }

    private static CompoundTag container(String id, int burnTime, CompoundTag... items) {
        CompoundTag.Builder tag = CompoundTag.builder().put("id", new StringTag(id)).put("x", new IntTag(5))
                .put("y", new IntTag(64)).put("z", new IntTag(5)).put("Items", ListTag.of(List.of(items)));
        return burnTime < 0 ? tag.build() : tag.put("lit_time_remaining", new IntTag(burnTime)).build();
    }

    private static ChunkData chunkWith(String block, CompoundTag blockEntity) {
        return setBlockEntity(set(TestChunks.empty(0, 0), 5, 64, 5, block), POS, blockEntity);
    }

    private static List<Tag> items(ChunkMergeResult result) {
        return ((ListTag) result.chunk().blockEntities().get(POS).get("Items")).elements();
    }

    @ParameterizedTest
    @EnumSource(Side.class)
    void bothSidesTakingFromOneChestMergesSlotBySlot(Side newer) throws Exception {
        // Scenario 6. Ours empties slot 0 and takes some iron; theirs empties slot 2, takes other iron, adds bread.
        ChunkData base = chunkWith("minecraft:chest", container("minecraft:chest", -1,
                item(0, "minecraft:diamond", 64), item(1, "minecraft:iron_ingot", 10), item(2, "minecraft:gold_ingot", 5)));
        ChunkData ours = chunkWith("minecraft:chest", container("minecraft:chest", -1,
                item(1, "minecraft:iron_ingot", 4), item(2, "minecraft:gold_ingot", 5)));
        ChunkData theirs = chunkWith("minecraft:chest", container("minecraft:chest", -1,
                item(0, "minecraft:diamond", 64), item(1, "minecraft:iron_ingot", 7), item(9, "minecraft:bread", 3)));
        ChunkMergeResult result = Merger.mergeChunk(base, ours, theirs, context(newer));

        assertEquals(List.of(item(1, "minecraft:iron_ingot", newer == Side.OURS ? 4 : 7), item(9, "minecraft:bread", 3)),
                items(result), "slots 0 and 2 were each emptied by one side; slot 9 was filled by one side");
        assertEquals(1, result.entries().size());
        ReportEntry conflict = result.entries().get(0);
        assertEquals(Category.CONTAINER_SLOT, conflict.category());
        assertEquals(Severity.CONFLICT, conflict.severity());
        assertEquals("slot 1", conflict.detail());
        assertEquals(POS, conflict.pos());
        // The merge never invents an item: every merged stack is a stack one of the inputs holds.
        Set<Tag> inputs = new TreeSet<>((a, b) -> a.toString().compareTo(b.toString()));
        for (ChunkData chunk : List.of(base, ours, theirs)) {
            inputs.addAll(((ListTag) chunk.blockEntities().get(POS).get("Items")).elements());
        }
        assertTrue(inputs.containsAll(items(result)));
    }

    @Test
    void aStackBothSidesMovedIsThereOnce() throws Exception {
        // Merging slot by slot sees a move as an emptied slot and a filled one. Both sides moving the same
        // stack, each to another slot, would leave it there twice.
        ChunkData base = chunkWith("minecraft:chest", container("minecraft:chest", -1, item(0, "minecraft:diamond", 64)));
        ChunkData ours = chunkWith("minecraft:chest", container("minecraft:chest", -1, item(5, "minecraft:diamond", 64)));
        ChunkData theirs = chunkWith("minecraft:chest", container("minecraft:chest", -1, item(7, "minecraft:diamond", 64)));
        ChunkMergeResult result = Merger.mergeChunk(base, ours, theirs, context(Side.THEIRS));
        assertEquals(List.of(item(7, "minecraft:diamond", 64)), items(result), "where the newer side put it");
        assertEquals(List.of("64 minecraft:diamond left out: both sides moved the same items"),
                result.entries().stream().map(ReportEntry::detail).toList());
        assertEquals(Category.CONTAINER_SLOT, result.entries().get(0).category());

        // One side moving a stack and the other adding to the chest is no surplus.
        ChunkData adds = chunkWith("minecraft:chest", container("minecraft:chest", -1, item(0, "minecraft:diamond", 64),
                item(3, "minecraft:diamond", 10)));
        ChunkMergeResult fine = Merger.mergeChunk(base, ours, adds, context(Side.THEIRS));
        assertEquals(List.of(item(3, "minecraft:diamond", 10), item(5, "minecraft:diamond", 64)), items(fine));
        assertTrue(fine.entries().isEmpty());

        // A furnace that burned one coal on each side is not a move: it is one side's furnace, with 7 coal.
        ChunkData cold = chunkWith("minecraft:furnace", container("minecraft:furnace", -1, item(1, "minecraft:coal", 8)));
        ChunkData oursBurned = chunkWith("minecraft:furnace", container("minecraft:furnace", -1, item(1, "minecraft:coal", 7),
                item(2, "minecraft:iron_ingot", 2)));
        ChunkData theirsBurned = chunkWith("minecraft:furnace", container("minecraft:furnace", -1, item(1, "minecraft:coal", 7),
                item(2, "minecraft:iron_ingot", 4)));
        ChunkMergeResult furnace = Merger.mergeChunk(cold, oursBurned, theirsBurned, context(Side.THEIRS));
        assertEquals(List.of("slot 2"), furnace.entries().stream().map(ReportEntry::detail).toList());
        assertEquals(List.of(item(1, "minecraft:coal", 7), item(2, "minecraft:iron_ingot", 4)), items(furnace));
    }

    @Test
    void aLootChestBothSidesOpenedIsTheNewerSidesNotBothRolls() throws Exception {
        CompoundTag sealed = CompoundTag.builder().put("id", new StringTag("minecraft:chest")).put("x", new IntTag(5))
                .put("y", new IntTag(64)).put("z", new IntTag(5)).put("LootTable", new StringTag("minecraft:chests/simple_dungeon")).build();
        ChunkData base = chunkWith("minecraft:chest", sealed);
        // Each side rolled the loot when it opened the chest. Ours then took the diamond.
        ChunkData ours = chunkWith("minecraft:chest", container("minecraft:chest", -1, item(3, "minecraft:bone", 2)));
        ChunkData theirs = chunkWith("minecraft:chest", container("minecraft:chest", -1, item(3, "minecraft:bone", 2),
                item(5, "minecraft:diamond", 1)));
        ChunkMergeResult result = Merger.mergeChunk(base, ours, theirs, context(Side.OURS));
        assertEquals(List.of(item(3, "minecraft:bone", 2)), items(result), "not the diamond ours already took");
        assertEquals(List.of("both sides opened this loot container"), result.entries().stream().map(ReportEntry::detail).toList());
        assertEquals(2, items(Merger.mergeChunk(base, ours, theirs, context(Side.THEIRS))).size());
    }

    @Test
    void furnaceProgressGoesToTheNewerSideWithoutAConflict() throws Exception {
        ChunkData base = chunkWith("minecraft:furnace", container("minecraft:furnace", 100, item(0, "minecraft:raw_iron", 8)));
        ChunkData ours = chunkWith("minecraft:furnace", container("minecraft:furnace", 40, item(0, "minecraft:raw_iron", 8), item(2, "minecraft:iron_ingot", 1)));
        ChunkData theirs = chunkWith("minecraft:furnace", container("minecraft:furnace", 75, item(0, "minecraft:raw_iron", 8)));
        ChunkMergeResult result = Merger.mergeChunk(base, ours, theirs, context(Side.THEIRS));
        assertTrue(result.entries().isEmpty(), "burn time is not a conflict");
        assertEquals(new IntTag(75), result.chunk().blockEntities().get(POS).get("lit_time_remaining"));
        assertEquals(2, items(result).size(), "the ingot ours smelted is kept");
    }

    @Test
    void anUnopenedLootChestStaysUnopenedUntilOneSideOpensIt() throws Exception {
        CompoundTag sealed = CompoundTag.builder().put("id", new StringTag("minecraft:chest")).put("x", new IntTag(5))
                .put("y", new IntTag(64)).put("z", new IntTag(5)).put("LootTable", new StringTag("minecraft:chests/simple_dungeon")).build();
        ChunkData base = chunkWith("minecraft:chest", sealed);
        ChunkData elsewhere = set(base, 1, 1, 1, "minecraft:stone");
        assertEquals(sealed, Merger.mergeChunk(base, elsewhere, set(base, 2, 2, 2, "minecraft:stone"), context(Side.OURS))
                .chunk().blockEntities().get(POS));
        ChunkData opened = chunkWith("minecraft:chest", container("minecraft:chest", -1, item(3, "minecraft:bone", 2)));
        CompoundTag merged = Merger.mergeChunk(base, opened, elsewhere, context(Side.THEIRS)).chunk().blockEntities().get(POS);
        assertFalse(merged.contains("LootTable"));
        assertEquals(List.of(item(3, "minecraft:bone", 2)), ((ListTag) merged.get("Items")).elements());
    }

    @Test
    void blocksThatNeverStoodTogetherAreListedForARefresh() throws Exception {
        // Ours puts up a fence; theirs puts stone right next to it. No input has a fence beside
        // stone, so the fence's stored connection may be stale and the game is asked to redo it.
        ChunkData base = TestChunks.empty(0, 0);
        ChunkData ours = set(base, 5, 64, 5, "minecraft:oak_fence");
        ChunkData theirs = set(base, 6, 64, 5, "minecraft:stone");
        ChunkMergeResult plain = Merger.mergeChunk(base, ours, theirs, context(Side.OURS));
        assertTrue(plain.chunk().postProcessing().stream().allMatch(List::isEmpty), "a single chunk merge does not look at seams");

        var result = DimensionMerger.merge(java.util.Map.of(base.pos(), base), java.util.Map.of(base.pos(), ours),
                java.util.Map.of(base.pos(), theirs), java.util.Map.of(), context(Side.OURS), DimensionMerger.Relight.CHANGED);
        List<List<Short>> lists = result.chunks().get(base.pos()).postProcessing();
        // Section 4 (y 64..79) is list 8 of an overworld chunk. Packed as x | y<<4 | z<<8 inside the section.
        assertEquals(List.of((short) (5 | 5 << 8), (short) (6 | 5 << 8)), lists.get(8));
        assertEquals(1, lists.stream().filter(list -> !list.isEmpty()).count());

        var oneSided = DimensionMerger.merge(java.util.Map.of(base.pos(), base), java.util.Map.of(base.pos(), set(ours, 6, 64, 5, "minecraft:stone")),
                java.util.Map.of(base.pos(), base), java.util.Map.of(), context(Side.OURS), DimensionMerger.Relight.CHANGED);
        assertTrue(oneSided.chunks().get(base.pos()).postProcessing().stream().allMatch(List::isEmpty),
                "what one side built on its own stood together in that side's world");
    }

    // ---- entities ---------------------------------------------------------------------------

    private static FlatEntity entity(int n, String id, double x, UUID vehicle, String... extra) {
        CompoundTag.Builder data = CompoundTag.builder().put("id", new StringTag(id)).put("UUID", Entities.toTag(new UUID(0, n)))
                .put("Pos", ListTag.of(List.of(new DoubleTag(x), new DoubleTag(64), new DoubleTag(0.5))));
        for (int i = 0; i < extra.length; i += 2) {
            data.put(extra[i], new StringTag(extra[i + 1]));
        }
        return new FlatEntity(new UUID(0, n), vehicle, List.of(), data.build());
    }

    private static UUID id(int n) {
        return new UUID(0, n);
    }

    @Test
    void entitiesMergeAsASetByUuid() throws Exception {
        EntityIndex base = EntityIndex.of(List.of(entity(1, "minecraft:cow", 1, null), entity(2, "minecraft:pig", 2, null),
                entity(3, "minecraft:sheep", 3, null), entity(4, "minecraft:cat", 4, null),
                entity(5, "minecraft:item_frame", 5, null, "Item", "map")));
        EntityIndex ours = EntityIndex.of(List.of(entity(1, "minecraft:cow", 1, null),      // unchanged
                // 2 removed by ours, unchanged by theirs -> removed
                // 3 removed by ours, moved by theirs -> removed: theirs only had it loaded
                entity(4, "minecraft:cat", 40, null),                                         // moved by both
                entity(5, "minecraft:item_frame", 5, null, "Item", "sword"),                  // item changed by both
                entity(6, "minecraft:bee", 6, null)));                                        // new on ours
        EntityIndex theirs = EntityIndex.of(List.of(entity(1, "minecraft:cow", 1, null), entity(2, "minecraft:pig", 2, null),
                entity(3, "minecraft:sheep", 33, null), entity(4, "minecraft:cat", 44, null),
                entity(5, "minecraft:item_frame", 5, null, "Item", "shield"), entity(7, "minecraft:fox", 7, null)));
        EntityMerger.Result result = EntityMerger.merge(base, ours, theirs, context(Side.THEIRS));

        assertEquals(Set.of(id(1), id(4), id(5), id(6), id(7)), result.entities().entities().keySet());
        assertEquals(theirs.get(id(4)), result.entities().get(id(4)));
        List<ReportEntry> entries = result.entries();
        assertEquals(3, entries.size());
        assertEquals(Severity.RESOLVED, entries.get(0).severity());
        assertTrue(entries.get(0).detail().endsWith("removed by ours, only loaded by theirs"), entries.get(0).detail());
        assertEquals(Severity.RESOLVED, entries.get(1).severity(), "a mob that wandered on both sides is not a conflict");
        assertEquals(Severity.CONFLICT, entries.get(2).severity(), "an item frame holding different items is");
        assertTrue(entries.get(2).detail().contains("items differ"));
        assertEquals(ours.get(id(4)), EntityMerger.merge(base, ours, theirs, context(Side.OURS)).entities().get(id(4)));
    }

    @Test
    void aRiderAppearsOnceWithTheVehicleOfTheSideThatWonIt() throws Exception {
        // Base: a pig and a zombie, apart. Ours puts the zombie on the pig. Theirs moves the zombie.
        FlatEntity pig = entity(1, "minecraft:pig", 1, null);
        EntityIndex base = EntityIndex.of(List.of(pig, entity(2, "minecraft:zombie", 9, null)));
        EntityIndex ours = EntityIndex.of(List.of(new FlatEntity(id(1), null, List.of(id(2)), pig.data()),
                entity(2, "minecraft:zombie", 1, id(1))));
        EntityIndex theirs = EntityIndex.of(List.of(pig, entity(2, "minecraft:zombie", 20, null)));

        EntityIndex riding = EntityMerger.merge(base, ours, theirs, context(Side.OURS)).entities();
        assertEquals(id(1), riding.get(id(2)).vehicle());
        assertEquals(List.of(id(2)), riding.get(id(1)).passengers());
        List<CompoundTag> topLevel = new ArrayList<>();
        riding.toChunks(5023).values().forEach(chunk -> topLevel.addAll(chunk.entities()));
        assertEquals(1, topLevel.size(), "the zombie is nested in the pig, not also on its own");

        EntityIndex walking = EntityMerger.merge(base, ours, theirs, context(Side.THEIRS)).entities();
        assertNull(walking.get(id(2)).vehicle());
        assertTrue(walking.get(id(1)).passengers().isEmpty(), "the pig does not keep a rider that walked away");
        assertEquals(2, walking.toChunks(5023).values().stream().mapToInt(chunk -> chunk.entities().size()).sum());
    }

    /** The player's file is the one written and holds whatever the player took along. */
    private static final EntityMerger.Surroundings HELD = new EntityMerger.Surroundings(uuid -> false, entity -> true, pos -> false);

    @Test
    void aVehicleAPlayerLeftTheGameInIsOneMoreVersionOfTheEntity() throws Exception {
        UUID player = new UUID(7, 7);
        // Base: a boat with a pig in it. Ours: a player left the game in the boat, so the player's file holds
        // both and the entity files hold neither. Theirs: the boat was rowed away.
        FlatEntity boat = new FlatEntity(id(1), null, List.of(id(2)), entity(1, "minecraft:oak_boat", 1, null).data());
        FlatEntity pig = entity(2, "minecraft:pig", 1, id(1));
        EntityIndex base = EntityIndex.of(List.of(boat, pig));
        EntityIndex ours = EntityIndex.of(List.of(boat.carriedBy(player), pig));
        EntityIndex moved = EntityIndex.of(List.of(
                new FlatEntity(id(1), null, List.of(id(2)), entity(1, "minecraft:oak_boat", 40, null).data()),
                entity(2, "minecraft:pig", 40, id(1))));

        // Untouched by the other side, the boat stays with the player if the player's file is the one written.
        EntityIndex kept = EntityMerger.merge(base, ours, base, context(Side.THEIRS), HELD).entities();
        assertEquals(Set.of(player), kept.carried().keySet());
        assertEquals(1, kept.carried().get(player).size());
        assertEquals(1, ((ListTag) kept.carried().get(player).get(0).get("Passengers")).size(), "the pig is still in the boat");
        assertTrue(kept.toChunks(5023).isEmpty(), "what the player's file holds is not in the entity files as well");

        // If that file is not the one written, the boat goes back into the world instead of being lost.
        EntityIndex back = EntityMerger.merge(base, ours, base, context(Side.THEIRS), EntityMerger.Surroundings.NONE).entities();
        assertTrue(back.carried().isEmpty());
        assertEquals(1, back.toChunks(5023).values().stream().mapToInt(chunk -> chunk.entities().size()).sum());

        // Rowed away on the other side: both changed it, so the newer side decides, and it is stored once.
        EntityIndex rowed = EntityMerger.merge(base, ours, moved, context(Side.THEIRS), HELD).entities();
        assertTrue(rowed.carried().isEmpty());
        assertEquals(moved.get(id(1)), rowed.get(id(1)));
        EntityIndex ridden = EntityMerger.merge(base, ours, moved, context(Side.OURS), HELD).entities();
        assertEquals(Set.of(player), ridden.carried().keySet());
        assertTrue(ridden.toChunks(5023).isEmpty());
    }

    @Test
    void aDroppedItemOneSidePickedUpStaysGone() throws Exception {
        // Ours picked the item up. Theirs only had the chunk loaded, so the item aged.
        FlatEntity dropped = entity(1, "minecraft:item", 1, null, "Item", "diamond", "Age", "10");
        FlatEntity aged = entity(1, "minecraft:item", 1, null, "Item", "diamond", "Age", "900");
        EntityIndex base = EntityIndex.of(List.of(dropped));
        EntityMerger.Result result = EntityMerger.merge(base, EntityIndex.of(List.of()), EntityIndex.of(List.of(aged)),
                context(Side.THEIRS));
        assertTrue(result.entities().entities().isEmpty(), "back on the ground it would exist twice: there and in the inventory");
        assertEquals(List.of(Severity.RESOLVED), result.entries().stream().map(ReportEntry::severity).toList());
        assertTrue(result.entries().get(0).detail().endsWith("removed by ours, only loaded by theirs"));

        // If theirs changed what the entity holds, that is a change by hand and it is kept.
        FlatEntity grown = entity(1, "minecraft:item", 1, null, "Item", "diamonds", "Age", "900");
        assertEquals(Set.of(id(1)), EntityMerger.merge(base, EntityIndex.of(List.of()), EntityIndex.of(List.of(grown)),
                context(Side.THEIRS)).entities().entities().keySet());
    }

    @Test
    void aCreatureOneSideNoLongerHasIsGoneUnlessTheOtherSideDidMoreThanLoadIt() throws Exception {
        // Ours slaughtered the cow. On theirs it wandered. Back in the world it would exist twice: there and as beef.
        EntityIndex cow = EntityIndex.of(List.of(entity(2, "minecraft:cow", 1, null)));
        for (Side newer : Side.values()) {
            EntityMerger.Result gone = EntityMerger.merge(cow, EntityIndex.of(List.of()),
                    EntityIndex.of(List.of(entity(2, "minecraft:cow", 7, null))), context(newer));
            assertTrue(gone.entities().entities().isEmpty());
            assertEquals(List.of(Severity.RESOLVED), gone.entries().stream().map(ReportEntry::severity).toList());
            // Theirs named it: that is a player's doing, and the cow stays.
            EntityMerger.Result named = EntityMerger.merge(cow, EntityIndex.of(List.of()),
                    EntityIndex.of(List.of(entity(2, "minecraft:cow", 7, null, "CustomName", "Bess"))), context(newer));
            assertEquals(Set.of(id(2)), named.entities().entities().keySet());
            assertTrue(named.entries().get(0).detail().endsWith("removed by ours, changed by theirs, kept"));
        }
    }

    private static EntityMerger.Surroundings around(List<EntityMerger.Ending> endings, EntityMerger.Nest nest) {
        return new EntityMerger.Surroundings(uuid -> false, entity -> false, pos -> false, endings, pos -> nest);
    }

    @Test
    void whatBothSidesEndedAndBeganAgainIsThereOnce() throws Exception {
        // A tadpole became a frog on both sides. A frog is a new entity, so each side has its own.
        EntityIndex base = EntityIndex.of(List.of(entity(1, "minecraft:tadpole", 1, null)));
        EntityIndex ours = EntityIndex.of(List.of(entity(10, "minecraft:frog", 3, null)));
        EntityIndex theirs = EntityIndex.of(List.of(entity(11, "minecraft:frog", 5, null)));
        EntityIndex none = EntityIndex.of(List.of());
        List<EntityMerger.Ending> grewUp = List.of(new EntityMerger.Ending(new BlockPos(1, 64, 0), "minecraft:tadpole",
                Set.of("minecraft:frog"), 1));
        assertEquals(Set.of(id(11)), EntityMerger.merge(none, ours, theirs, context(Side.THEIRS), around(grewUp, null))
                .entities().entities().keySet());
        EntityMerger.Result result = EntityMerger.merge(none, ours, theirs, context(Side.OURS), around(grewUp, null));
        assertEquals(Set.of(id(10)), result.entities().entities().keySet());
        assertEquals(List.of(Severity.RESOLVED), result.entries().stream().map(ReportEntry::severity).toList());
        assertTrue(result.entries().get(0).detail().startsWith("1 minecraft:frog of theirs left out"), result.entries().get(0).detail());
        // Without the ending both frogs are somebody's, and so they are if ours named its frog or it is far away.
        assertEquals(2, EntityMerger.merge(none, ours, theirs, context(Side.THEIRS)).entities().entities().size());
        EntityIndex named = EntityIndex.of(List.of(entity(10, "minecraft:frog", 3, null, "CustomName", "Kermit")));
        assertEquals(2, EntityMerger.merge(none, named, theirs, context(Side.THEIRS), around(grewUp, null)).entities().entities().size());
        EntityIndex far = EntityIndex.of(List.of(entity(10, "minecraft:frog", 300, null)));
        assertEquals(2, EntityMerger.merge(none, far, theirs, context(Side.THEIRS), around(grewUp, null)).entities().entities().size());
        // It became a frog on theirs only. On ours it is still a tadpole, which theirs no longer has: gone.
        EntityIndex still = EntityIndex.of(List.of(entity(1, "minecraft:tadpole", 2, null)));
        assertEquals(Set.of(id(11)), EntityMerger.merge(base, still, theirs, context(Side.OURS)).entities().entities().keySet());

        // Frogspawn hatched on both sides: five tadpoles on ours, three on theirs and one of them a frog by now.
        List<FlatEntity> five = new ArrayList<>();
        for (int n = 20; n < 25; n++) {
            five.add(entity(n, "minecraft:tadpole", n - 15, null));
        }
        EntityIndex three = EntityIndex.of(List.of(entity(30, "minecraft:tadpole", 4, null), entity(31, "minecraft:tadpole", 5, null),
                entity(32, "minecraft:frog", 6, null)));
        List<EntityMerger.Ending> spawn = List.of(new EntityMerger.Ending(new BlockPos(5, 64, 0), "minecraft:frogspawn",
                Set.of("minecraft:tadpole", "minecraft:frog"), 5));
        Set<UUID> kept = EntityMerger.merge(none, EntityIndex.of(five), three, context(Side.THEIRS), around(spawn, null))
                .entities().entities().keySet();
        assertEquals(Set.of(id(30), id(31), id(32), id(23), id(24)), kept, "theirs' three, and the two of ours furthest from the spawn");
    }

    private static FlatEntity bee(int n, double x) {
        FlatEntity bee = entity(n, "minecraft:bee", x, null);
        return new FlatEntity(bee.uuid(), null, List.of(), bee.data().with("hive_pos", new IntArrayTag(new int[] {3, 65, 0})));
    }

    @Test
    void beesOfANestBothSidesChangedAreTheNewerSides() throws Exception {
        EntityIndex none = EntityIndex.of(List.of());
        EntityIndex out = EntityIndex.of(List.of(bee(1, 1), bee(2, 2), bee(3, 3)));
        // All three rest in theirs' nest, and theirs is newer. On ours they were out, as new entities.
        EntityMerger.Result full = EntityMerger.merge(none, out, none, context(Side.THEIRS),
                around(List.of(), new EntityMerger.Nest(Side.THEIRS, 3)));
        assertTrue(full.entities().entities().isEmpty(), "the nest is full: with ours' bees there would be six");
        assertTrue(full.entries().get(0).detail().startsWith("3 minecraft:bee of ours left out"), full.entries().get(0).detail());
        // Theirs' nest holds one bee and none of theirs flies: there is room for two.
        assertEquals(Set.of(id(1), id(2)), EntityMerger.merge(none, out, none, context(Side.THEIRS),
                around(List.of(), new EntityMerger.Nest(Side.THEIRS, 1))).entities().entities().keySet());
        // One in theirs' nest and one of theirs in the air: room for one.
        EntityIndex one = EntityIndex.of(List.of(bee(9, 4)));
        assertEquals(Set.of(id(1), id(9)), EntityMerger.merge(none, out, one, context(Side.THEIRS),
                around(List.of(), new EntityMerger.Nest(Side.THEIRS, 1))).entities().entities().keySet());
        // The nest is ours and empty, and ours' bees are out: they are the nest's bees.
        assertEquals(3, EntityMerger.merge(none, out, none, context(Side.OURS),
                around(List.of(), new EntityMerger.Nest(Side.OURS, 0))).entities().entities().size());

        // A bee that was out in the base. On theirs, whose nest it is, it went in; on ours it flew on.
        EntityIndex base = EntityIndex.of(List.of(bee(1, 1)));
        EntityIndex flewOn = EntityIndex.of(List.of(bee(1, 5)));
        assertTrue(EntityMerger.merge(base, flewOn, none, context(Side.THEIRS), around(List.of(), new EntityMerger.Nest(Side.THEIRS, 1)))
                .entities().entities().isEmpty(), "it is in the nest");
        // The other way round: it went into ours' nest, which is not in the merged world. Theirs' bee flies on.
        assertEquals(Set.of(id(1)), EntityMerger.merge(base, none, flewOn, context(Side.THEIRS),
                around(List.of(), new EntityMerger.Nest(Side.THEIRS, 0))).entities().entities().keySet());
        // A nest that only one side changed needs none of this: the bees merge like any entity.
        assertEquals(3, EntityMerger.merge(none, out, none, context(Side.THEIRS)).entities().entities().size());
    }

    @Test
    void oneUuidOnBothSidesWithoutABaseIsOneEntityNotAConflict() throws Exception {
        // The base holds the entities of an unfinished chunk inside the chunk, where the entity merge does not see them.
        EntityIndex none = EntityIndex.of(List.of());
        EntityMerger.Result moved = EntityMerger.merge(none, EntityIndex.of(List.of(entity(1, "minecraft:villager", 1, null))),
                EntityIndex.of(List.of(entity(1, "minecraft:villager", 5, null))), context(Side.OURS));
        assertEquals(List.of(Severity.RESOLVED), moved.entries().stream().map(ReportEntry::severity).toList());
        EntityMerger.Result items = EntityMerger.merge(none,
                EntityIndex.of(List.of(entity(1, "minecraft:villager", 1, null, "Inventory", "bread"))),
                EntityIndex.of(List.of(entity(1, "minecraft:villager", 1, null, "Inventory", "wheat"))), context(Side.OURS));
        assertEquals(List.of(Severity.CONFLICT), items.entries().stream().map(ReportEntry::severity).toList());
    }

    @Test
    void terrainBothSidesGeneratedDoesNotGetItsWildlifeTwice() throws Exception {
        // Seen in the game: the same chunks generated twice hold the same chickens and loot carts under other UUIDs.
        EntityMerger.Surroundings generatedByBoth = new EntityMerger.Surroundings(uuid -> false, entity -> false, pos -> true);
        EntityIndex none = EntityIndex.of(List.of());
        EntityIndex ours = EntityIndex.of(List.of(
                entity(1, "minecraft:chicken", 1, null, "Health", "4"), entity(2, "minecraft:chicken", 2, null, "Health", "4"),
                entity(3, "minecraft:chicken", 3, null, "Health", "4"),                              // one more than theirs: bred
                entity(4, "minecraft:chicken", 4, null, "Health", "4", "CustomName", "Henrietta"),  // named by a player
                entity(5, "minecraft:chest_minecart", 5, null, "LootTable", "mineshaft"),
                entity(6, "minecraft:chest_minecart", 6, null, "Items", "rails"),                    // opened and filled
                entity(7, "minecraft:oak_boat", 7, null)));                                          // placed by a player
        EntityIndex theirs = EntityIndex.of(List.of(
                entity(11, "minecraft:chicken", 1, null, "Health", "4"), entity(12, "minecraft:chicken", 2, null, "Health", "4"),
                entity(15, "minecraft:chest_minecart", 5, null, "LootTable", "mineshaft"),
                entity(17, "minecraft:oak_boat", 9, null)));
        EntityMerger.Result result = EntityMerger.merge(none, ours, theirs, context(Side.THEIRS), generatedByBoth);
        assertEquals(Set.of(id(3), id(4), id(6), id(7), id(11), id(12), id(15), id(17)), result.entities().entities().keySet(),
                "theirs' wildlife and unopened cart, plus what ours has beyond that and what shows a player's hand");
        assertEquals(List.of("2 minecraft:chicken of ours left out: both sides generated this terrain, and theirs has as many"
                + " of its own there", "1 minecraft:chest_minecart of ours left out: both sides generated this terrain, and theirs"
                + " has as many of its own there"), result.entries().stream().map(ReportEntry::detail).sorted(java.util.Comparator.reverseOrder()).toList());
        // The same with the sides swapped: it is the newer side's that stay.
        assertEquals(Set.of(id(1), id(2), id(3), id(4), id(5), id(6), id(7), id(17)),
                EntityMerger.merge(none, ours, theirs, context(Side.OURS), generatedByBoth).entities().entities().keySet());
        // Where the base has the terrain, an entity new on one side is somebody's and is kept.
        assertEquals(11, EntityMerger.merge(none, ours, theirs, context(Side.THEIRS)).entities().entities().size());
    }

    @Test
    void aLeashHeldByAPlayerIsNotDangling() throws Exception {
        UUID player = new UUID(7, 7);
        CompoundTag leashed = entity(2, "minecraft:cow", 2, null).data().with("leash", CompoundTag.of("UUID", Entities.toTag(player)));
        EntityIndex ours = EntityIndex.of(List.of(new FlatEntity(id(2), null, List.of(), leashed)));
        EntityMerger.Surroundings withPlayer = new EntityMerger.Surroundings(player::equals, entity -> false, pos -> false);
        assertTrue(EntityMerger.merge(EntityIndex.of(List.of()), ours, EntityIndex.of(List.of()), context(Side.OURS), withPlayer)
                .entries().isEmpty());
    }

    @Test
    void aLeashToAnEntityThatIsGoneIsReported() throws Exception {
        CompoundTag leashed = entity(2, "minecraft:cow", 2, null).data()
                .with("leash", CompoundTag.of("UUID", Entities.toTag(id(1))));
        EntityIndex base = EntityIndex.of(List.of(entity(1, "minecraft:villager", 1, null), new FlatEntity(id(2), null, List.of(), leashed)));
        EntityIndex ours = EntityIndex.of(List.of(new FlatEntity(id(2), null, List.of(), leashed)));   // the holder is gone
        EntityMerger.Result result = EntityMerger.merge(base, ours, base, context(Side.OURS));
        assertEquals(Set.of(id(2)), result.entities().entities().keySet());
        assertEquals(List.of(Category.ENTITY_REFERENCE), result.entries().stream().map(ReportEntry::category).toList());
    }


    // ---- block entities with timers -----------------------------------------------------------

    private static CompoundTag vault(long resumesAt, String key) {
        return CompoundTag.builder().put("id", new StringTag("minecraft:vault")).put("x", new IntTag(5)).put("y", new IntTag(64))
                .put("z", new IntTag(5))
                .put("config", CompoundTag.of("key_item", CompoundTag.builder().put("id", new StringTag(key)).put("count", new IntTag(1)).build()))
                .put("server_data", CompoundTag.of("state_updating_resumes_at", new mycelium.nbt.LongTag(resumesAt)))
                .put("shared_data", CompoundTag.empty()).build();
    }

    private static CompoundTag beehive(int... ticksInHive) {
        List<CompoundTag> bees = new ArrayList<>();
        for (int ticks : ticksInHive) {
            bees.add(CompoundTag.builder().put("entity_data", CompoundTag.of("id", new StringTag("minecraft:bee")))
                    .put("min_ticks_in_hive", new IntTag(600)).put("ticks_in_hive", new IntTag(ticks)).build());
        }
        return CompoundTag.builder().put("id", new StringTag("minecraft:beehive")).put("x", new IntTag(5)).put("y", new IntTag(64))
                .put("z", new IntTag(5)).put("bees", ListTag.of(bees)).build();
    }

    @ParameterizedTest
    @EnumSource(Side.class)
    void timersInsideVaultsAndBeehivesGoToTheNewerSideWithoutAConflict(Side newer) throws Exception {
        // A vault ticks on both sides whenever its chunk is loaded.
        ChunkData ours = chunkWith("minecraft:vault", vault(4041, "minecraft:trial_key"));
        ChunkData theirs = chunkWith("minecraft:vault", vault(4040, "minecraft:trial_key"));
        ChunkMergeResult vaults = Merger.mergeChunk(chunkWith("minecraft:vault", vault(150, "minecraft:trial_key")), ours, theirs, context(newer));
        assertTrue(vaults.entries().isEmpty(), "a vault's next update time is not a conflict");
        assertEquals((newer == Side.OURS ? ours : theirs).blockEntities().get(POS), vaults.chunk().blockEntities().get(POS));

        // One bee left the hive on both sides; the other has been counting ticks.
        ours = chunkWith("minecraft:beehive", beehive(466));
        theirs = chunkWith("minecraft:beehive", beehive(467));
        ChunkMergeResult hives = Merger.mergeChunk(chunkWith("minecraft:beehive", beehive(509, 347)), ours, theirs, context(newer));
        assertTrue(hives.entries().isEmpty(), "the time a bee has spent in its hive is not a conflict");
        assertEquals((newer == Side.OURS ? ours : theirs).blockEntities().get(POS), hives.chunk().blockEntities().get(POS));
    }

    @Test
    void aRealChangeToAVaultStillCountsNextToItsTimer() throws Exception {
        ChunkData base = chunkWith("minecraft:vault", vault(150, "minecraft:trial_key"));
        ChunkData ticked = chunkWith("minecraft:vault", vault(4041, "minecraft:trial_key"));
        ChunkData rekeyed = chunkWith("minecraft:vault", vault(4040, "minecraft:ominous_trial_key"));
        // Only theirs changed something besides the timer, so theirs' vault is the result, even if ours is newer.
        ChunkMergeResult oneSided = Merger.mergeChunk(base, ticked, rekeyed, context(Side.OURS));
        assertTrue(oneSided.entries().isEmpty());
        assertEquals(rekeyed.blockEntities().get(POS), oneSided.chunk().blockEntities().get(POS));
        // A side that changed nothing at all leaves the other side's vault alone, timer included.
        assertEquals(ticked.blockEntities().get(POS),
                Merger.mergeChunk(base, ticked, base, context(Side.THEIRS)).chunk().blockEntities().get(POS));
        // Both sides changing the key is a conflict.
        ChunkMergeResult both = Merger.mergeChunk(base, chunkWith("minecraft:vault", vault(4041, "minecraft:stick")), rekeyed, context(Side.OURS));
        assertEquals(1, both.entries().size());
        assertEquals(Severity.CONFLICT, both.entries().get(0).severity());
    }
}
