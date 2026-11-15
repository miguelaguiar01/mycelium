package mycelium.chunk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import mycelium.nbt.ByteTag;
import mycelium.nbt.CompoundTag;
import mycelium.nbt.IntTag;
import mycelium.nbt.ListTag;
import mycelium.nbt.LongArrayTag;
import mycelium.nbt.StringTag;
import mycelium.nbt.Tag;
import mycelium.testing.Fixtures;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** The chunk codec against every chunk of the real 26.3 fixture worlds. */
class ChunkCodecFixtureTest {

    private static final String ZLIB = "probe-zlib";

    static List<String> worlds() {
        return Fixtures.WORLDS;
    }

    @ParameterizedTest
    @MethodSource("worlds")
    void everyChunkDecodesAndEncodesBackToTheSameNbt(String world) throws ChunkFormatException {
        Map<String, Integer> statuses = new TreeMap<>();
        for (Fixtures.FixtureChunk fixture : Fixtures.chunks(world, "region")) {
            ChunkData chunk = ChunkCodec.decode(fixture.root());
            assertEquals(fixture.root(), ChunkCodec.encode(chunk), fixture::toString);
            assertEquals(chunk, ChunkCodec.decode(ChunkCodec.encode(chunk)), fixture::toString);

            assertEquals(DataVersions.MC_26_3, chunk.dataVersion());
            assertEquals(fixture.slot(), mycelium.region.RegionPos.slotIndex(chunk.pos().x(), chunk.pos().z()));
            assertEquals(-4, chunk.minSectionY());
            statuses.merge(chunk.status(), 1, Integer::sum);
            // isLightOn exists exactly on finished chunks; unfinished ones keep their entities in the chunk.
            assertEquals(chunk.isFull(), chunk.lightOn() != null, fixture::toString);
            assertEquals(!chunk.isFull(), chunk.extra().contains("entities"), fixture::toString);
            assertEquals(chunk.isFull() ? List.of() : List.of("entities"), List.copyOf(chunk.extra().keys()),
                    "no other tag is left unmodelled");
            assertEquals(24, chunk.sections().stream().filter(section -> !section.isLightOnly()).count());
            assertEquals(24, chunk.postProcessing().size());
        }
        assertEquals(56, statuses.get(ChunkData.STATUS_FULL), "finished chunks in " + world);
        assertTrue(statuses.size() == 5 && statuses.get("minecraft:structure_starts") > 200,
                "most chunks on disk are unfinished: " + statuses);
    }

    @Test
    void containerItemsAreWhereTheProbeScriptPutThem() throws ChunkFormatException {
        ChunkData chunk = ChunkCodec.decode(Fixtures.chunkRoot(ZLIB, 46, 47));
        CompoundTag chest = chunk.blockEntities().get(new BlockPos(736, 240, 753));
        assertEquals(new StringTag("minecraft:chest"), chest.get("id"));
        ListTag items = (ListTag) chest.get("Items");
        assertEquals(3, items.size());
        CompoundTag diamonds = (CompoundTag) items.get(0);
        assertEquals(new ByteTag((byte) 0), diamonds.get("Slot"));
        assertEquals(new StringTag("minecraft:diamond"), diamonds.get("id"));
        assertEquals(new IntTag(64), diamonds.get("count"));
        assertEquals(new ByteTag((byte) 26), ((CompoundTag) items.get(2)).get("Slot"));

        // The second chest was filled with /item; its shulker box item nests a container component.
        CompoundTag second = chunk.blockEntities().get(new BlockPos(738, 240, 753));
        CompoundTag shulkerItem = (CompoundTag) ((ListTag) second.get("Items")).get(4);
        ListTag nested = (ListTag) ((CompoundTag) shulkerItem.get("components")).get("minecraft:container");
        assertEquals(new IntTag(12), ((CompoundTag) ((CompoundTag) nested.get(0)).get("item")).get("count"));

        // A chest that still holds its loot table has no Items yet.
        CompoundTag loot = chunk.blockEntities().get(new BlockPos(742, 240, 753));
        assertEquals(new StringTag("minecraft:chests/simple_dungeon"), loot.get("LootTable"));
        assertFalse(loot.contains("Items"));

        assertEquals(BlockState.of("minecraft:chest"), chunk.blockState(736, 240, 753), "a default state is stored by name alone");
        assertEquals(BlockState.parse("minecraft:chest[facing=north,type=left,waterlogged=false]"),
                chunk.blockState(740, 240, 753), "left half of a double chest");
        assertEquals(BlockState.parse("minecraft:chest[facing=north,type=right,waterlogged=false]"),
                chunk.blockState(741, 240, 753), "right half, one step clockwise from the facing");
        assertTrue(chunk.blockEntities().size() >= 50);
    }

    @Test
    void scheduledTicksAreDecoded() throws ChunkFormatException {
        ChunkData chunk = ChunkCodec.decode(Fixtures.chunkRoot(ZLIB, 47, 47));
        assertEquals(List.of(new ScheduledTick("minecraft:sand", 0, 2, new BlockPos(754, 248, 758), CompoundTag.empty())),
                chunk.blockTicks());
        assertTrue(chunk.fluidTicks().contains(
                new ScheduledTick("minecraft:water", 0, 5, new BlockPos(756, 240, 758), CompoundTag.empty())));
        assertTrue(chunk.fluidTicks().contains(
                new ScheduledTick("minecraft:lava", 0, 30, new BlockPos(760, 240, 758), CompoundTag.empty())));
    }

    @Test
    void multiBlockShapesAreDecodedWithTheirLinkingProperties() throws ChunkFormatException {
        ChunkData chunk = ChunkCodec.decode(Fixtures.chunkRoot(ZLIB, 47, 47));
        // The lower half of a closed, north-facing door and the foot of a north-facing bed are their
        // blocks' default states, so the file names the block and nothing else. Whoever pairs the
        // halves needs the block's default property values to see "half=lower" here.
        assertEquals(BlockState.of("minecraft:oak_door"), chunk.blockState(753, 240, 753));
        assertEquals(BlockState.parse(
                "minecraft:oak_door[facing=north,half=upper,hinge=left,open=false,powered=false]"),
                chunk.blockState(753, 241, 753));
        assertEquals(BlockState.of("minecraft:red_bed"), chunk.blockState(755, 240, 754));
        assertEquals("head", chunk.blockState(755, 240, 753).properties().get("part"));
        assertNull(chunk.blockEntities().get(new BlockPos(755, 240, 754)), "beds have no block entity in 26.3");
        // The piston was extended by the game itself, so its head is a block the script never placed.
        assertEquals("true", chunk.blockState(754, 240, 765).properties().get("extended"));
        assertEquals("minecraft:piston_head", chunk.blockState(754, 241, 765).name());
        // A double chest across the chunk border: each half is in its own chunk.
        ChunkData east = ChunkCodec.decode(Fixtures.chunkRoot(ZLIB, 48, 47));
        assertEquals("left", chunk.blockState(767, 240, 756).properties().get("type"));
        assertEquals("right", east.blockState(768, 240, 756).properties().get("type"));
        assertNotNull(east.blockEntities().get(new BlockPos(768, 240, 756)));
    }

    /** What the merge writes for a chunk it relights: no heightmaps, no light flag, no light arrays, no light-only sections. */
    private static ChunkData stripped(ChunkData chunk) {
        return new ChunkData(chunk.dataVersion(), chunk.pos(), chunk.minSectionY(), chunk.status(),
                chunk.lastUpdate(), chunk.inhabitedTime(),
                chunk.sections().stream().filter(section -> !section.isLightOnly()).map(Section::withoutLight).toList(),
                chunk.blockEntities(), chunk.blockTicks(), chunk.fluidTicks(), null, null, chunk.structures(),
                chunk.postProcessing(), chunk.extra());
    }

    @Test
    void derivedDataIsModelledSoItCanBeStripped() throws ChunkFormatException {
        ChunkData chunk = ChunkCodec.decode(Fixtures.chunkRoot(ZLIB, 47, 47));
        assertEquals(Boolean.TRUE, chunk.lightOn());
        assertEquals(List.of("MOTION_BLOCKING", "MOTION_BLOCKING_NO_LEAVES", "OCEAN_FLOOR", "WORLD_SURFACE"),
                chunk.heightmaps().keys().stream().sorted().toList());
        assertEquals(37, ((LongArrayTag) chunk.heightmaps().get("WORLD_SURFACE")).size());
        assertTrue(chunk.sections().stream().anyMatch(section -> section.skyLight() != null));
        assertTrue(chunk.sections().stream().anyMatch(section -> section.blockLight() != null));
        Section top = chunk.sections().get(chunk.sections().size() - 1);
        assertTrue(top.y() == 20 && top.isLightOnly(), "a light-only section sits above the top of the world");

        ChunkData stripped = stripped(chunk);
        CompoundTag encoded = ChunkCodec.encode(stripped);
        assertFalse(encoded.contains("Heightmaps"));
        assertFalse(encoded.contains("isLightOn"));
        for (Tag section : (ListTag) encoded.get("sections")) {
            assertFalse(((CompoundTag) section).contains("SkyLight") || ((CompoundTag) section).contains("BlockLight"));
        }
        assertEquals(24, ((ListTag) encoded.get("sections")).size());
        assertEquals(stripped, ChunkCodec.decode(encoded));
    }

    @ParameterizedTest
    @MethodSource("worlds")
    void derivedDataComesOffTheStoredTagsAsItDoesOffTheDecodedChunk(String world) throws ChunkFormatException {
        int finished = 0;
        for (Fixtures.FixtureChunk fixture : Fixtures.chunks(world, "region")) {
            ChunkData chunk = ChunkCodec.decode(fixture.root());
            CompoundTag bare = ChunkCodec.withoutDerivedData(fixture.root(), chunk.pos());
            if (chunk.isFull()) {
                // The quick way, on the tags, gives what the long way gives: decode, drop, encode.
                assertEquals(ChunkCodec.encode(stripped(chunk)), bare, fixture::toString);
                finished++;
            } else {
                assertSame(fixture.root(), bare, "an unfinished chunk keeps its light");
            }
        }
        assertEquals(56, finished);

        CompoundTag root = Fixtures.chunkRoot(ZLIB, 47, 47);
        ChunkPos pos = new ChunkPos(47, 47);
        assertNull(ChunkCodec.withoutDerivedData(root, new ChunkPos(48, 47)), "the chunk of another position");
        assertNull(ChunkCodec.withoutDerivedData(root.with("DataVersion", new IntTag(4671)), pos), "another version's chunk");
        assertNull(ChunkCodec.withoutDerivedData(root.without("sections"), pos));
        assertNull(ChunkCodec.withoutDerivedData(root.with("sections", ListTag.of(List.of(new IntTag(1)))), pos));
    }

    @Test
    void structureDataIsKeptPerStructure() throws ChunkFormatException {
        int starts = 0;
        int references = 0;
        for (Fixtures.FixtureChunk fixture : Fixtures.chunks(ZLIB, "region")) {
            StructureData structures = ChunkCodec.decode(fixture.root()).structures();
            assertTrue(structures.extra().isEmpty());
            starts += structures.starts().size();
            references += structures.references().size();
        }
        assertTrue(starts > 0 && references > 0, "the fixture has structure starts (" + starts + ") and references (" + references + ")");
    }

    @Test
    void unknownTagsAreCarriedThrough() throws ChunkFormatException {
        CompoundTag root = Fixtures.chunkRoot(ZLIB, 46, 47);
        ListTag sections = (ListTag) root.get("sections");
        CompoundTag moddedSection = ((CompoundTag) sections.get(3)).with("modded:section_data", new IntTag(42));
        List<Tag> changed = new java.util.ArrayList<>(sections.elements());
        changed.set(3, moddedSection);
        CompoundTag modded = root
                .with("modded:chunk_data", CompoundTag.of("answer", new IntTag(42)))
                .with("sections", ListTag.of(changed));

        ChunkData chunk = ChunkCodec.decode(modded);
        assertEquals(CompoundTag.of("answer", new IntTag(42)), chunk.extra().get("modded:chunk_data"));
        assertEquals(new IntTag(42), chunk.sections().get(3).extra().get("modded:section_data"));
        assertEquals(modded, ChunkCodec.encode(chunk));
    }

    @Test
    void otherDataVersionsAreRefused() {
        CompoundTag root = Fixtures.chunkRoot(ZLIB, 46, 47);
        ChunkFormatException older = assertThrows(ChunkFormatException.class,
                () -> ChunkCodec.decode(root.with("DataVersion", new IntTag(4671))));
        assertTrue(older.getMessage().contains("4671") && older.getMessage().contains("5023")
                && older.getMessage().contains("46,47"), older.getMessage());
        assertThrows(ChunkFormatException.class, () -> ChunkCodec.decode(root.with("DataVersion", new IntTag(5024))));
        assertThrows(ChunkFormatException.class, () -> ChunkCodec.decode(root.without("DataVersion")));
    }

    @Test
    void unexpectedLayoutsAreRefused() {
        CompoundTag root = Fixtures.chunkRoot(ZLIB, 46, 47);
        assertThrows(ChunkFormatException.class, () -> ChunkCodec.decode(root.without("sections")));
        assertThrows(ChunkFormatException.class, () -> ChunkCodec.decode(root.with("xPos", new StringTag("46"))));
        assertThrows(ChunkFormatException.class, () -> ChunkCodec.decode(root.without("Status")));
        // A block entity that claims a position in another chunk.
        ListTag blockEntities = (ListTag) root.get("block_entities");
        List<Tag> moved = new java.util.ArrayList<>(blockEntities.elements());
        moved.set(0, ((CompoundTag) moved.get(0)).with("x", new IntTag(0)));
        assertThrows(ChunkFormatException.class, () -> ChunkCodec.decode(root.with("block_entities", ListTag.of(moved))));
        // Two block entities at one position.
        List<Tag> doubled = new java.util.ArrayList<>(blockEntities.elements());
        doubled.add(doubled.get(0));
        assertThrows(ChunkFormatException.class, () -> ChunkCodec.decode(root.with("block_entities", ListTag.of(doubled))));
    }

    @Test
    void tagsTheGameToleratesAsAbsentDecodeToEmpty() throws ChunkFormatException {
        CompoundTag root = Fixtures.chunkRoot(ZLIB, 49, 48);
        CompoundTag bare = root.without("block_ticks", "fluid_ticks", "PostProcessing", "structures", "block_entities",
                "Heightmaps", "InhabitedTime", "LastUpdate", "isLightOn");
        ChunkData chunk = ChunkCodec.decode(bare);
        assertTrue(chunk.blockTicks().isEmpty() && chunk.fluidTicks().isEmpty() && chunk.blockEntities().isEmpty());
        assertNull(chunk.heightmaps());
        assertNull(chunk.lightOn());
        assertNull(chunk.structures());
        assertEquals(0, chunk.lastUpdate());
        assertEquals(chunk, ChunkCodec.decode(ChunkCodec.encode(chunk)));
    }
}
