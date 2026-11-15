package mycelium.chunk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import mycelium.json.Json;
import mycelium.nbt.CompoundTag;
import mycelium.nbt.ListTag;
import mycelium.nbt.Tag;
import mycelium.testing.Fixtures;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Section decoding against real 26.3 sections and against the values the probe script placed. */
class SectionCodecFixtureTest {

    static List<String> worlds() {
        return Fixtures.WORLDS;
    }

    @ParameterizedTest
    @MethodSource("worlds")
    void everySectionDecodesAndEncodesBackToTheSameNbt(String world) throws ChunkFormatException {
        int sections = 0;
        int lightOnly = 0;
        TreeSet<Integer> blockBits = new TreeSet<>();
        TreeSet<Integer> biomeBits = new TreeSet<>();
        for (Fixtures.FixtureChunk chunk : Fixtures.chunks(world, "region")) {
            for (Tag element : (ListTag) chunk.root().get("sections")) {
                CompoundTag tag = (CompoundTag) element;
                Section section = SectionCodec.decode(tag, chunk.toString());
                assertEquals(tag, SectionCodec.encode(section), () -> chunk + " section " + section.y());
                assertEquals(section, SectionCodec.decode(SectionCodec.encode(section), chunk.toString()));
                sections++;
                if (section.isLightOnly()) {
                    lightOnly++;
                    continue;
                }
                blockBits.add(section.blockStates().isUniform() ? 0
                        : PalettedContainer.bitsFor(section.blockStates().palette().size(), 4));
                biomeBits.add(section.biomes().isUniform() ? 0
                        : PalettedContainer.bitsFor(section.biomes().palette().size(), 1));
            }
        }
        assertTrue(sections > 8000, world + " has sections");
        // The fixtures must keep exercising every packing width they were built to cover.
        if (world.equals("probe-zlib")) {
            assertEquals(new TreeSet<>(List.of(0, 4, 5, 6, 7, 8, 9, 10, 11, 12)), blockBits);
            assertEquals(new TreeSet<>(List.of(0, 1, 2, 3, 4, 5, 6)), biomeBits);
            assertTrue(lightOnly > 0, "light-only sections above the top of the world are covered");
        } else {
            assertTrue(blockBits.containsAll(List.of(0, 4, 5, 12)), world + " block widths " + blockBits);
        }
    }

    @ParameterizedTest
    @MethodSource("worlds")
    void blockStatesMatchWhatTheProbeScriptPlaced(String world) throws ChunkFormatException {
        List<Object> expected = Json.array(Fixtures.expected(world).get("blockSections"), "expected.json");
        assertTrue(expected.size() >= 3);
        for (Object item : expected) {
            Map<String, Object> wanted = Json.object(item, "expected.json");
            List<Object> chunkPos = Json.array(wanted.get("chunk"), "expected.json");
            int sectionY = (Integer) wanted.get("sectionY");
            List<Object> states = Json.array(wanted.get("states"), "expected.json");
            List<Object> cells = Json.array(wanted.get("cells"), "expected.json");
            ChunkData chunk = ChunkCodec.decode(Fixtures.chunkRoot(world, (Integer) chunkPos.get(0), (Integer) chunkPos.get(1)));
            Section section = chunk.section(sectionY);
            assertEquals(states.size(), section.blockStates().palette().size(),
                    "section " + sectionY + " holds exactly the states placed");
            for (int index = 0; index < Section.BLOCKS; index++) {
                BlockState want = BlockState.parse((String) states.get((Integer) cells.get(index)));
                assertEquals(want, section.blockStates().get(index), "cell " + index + " of section " + sectionY);
            }
            // The same cell through absolute coordinates: index = y*256 + z*16 + x.
            int x = chunk.pos().x() * 16 + 3;
            int y = sectionY * 16 + 7;
            int z = chunk.pos().z() * 16 + 11;
            assertEquals(BlockState.parse((String) states.get((Integer) cells.get(7 * 256 + 11 * 16 + 3))),
                    chunk.blockState(x, y, z));
        }
    }

    @ParameterizedTest
    @MethodSource("worlds")
    void biomesMatchWhatTheProbeScriptPlaced(String world) throws ChunkFormatException {
        List<Object> expected = Json.array(Fixtures.expected(world).get("biomeSections"), "expected.json");
        assertTrue(!expected.isEmpty());
        for (Object item : expected) {
            Map<String, Object> wanted = Json.object(item, "expected.json");
            List<Object> chunkPos = Json.array(wanted.get("chunk"), "expected.json");
            int sectionY = (Integer) wanted.get("sectionY");
            List<Object> biomes = Json.array(wanted.get("biomes"), "expected.json");
            List<Object> cells = Json.array(wanted.get("cells"), "expected.json");
            ChunkData chunk = ChunkCodec.decode(Fixtures.chunkRoot(world, (Integer) chunkPos.get(0), (Integer) chunkPos.get(1)));
            Section section = chunk.section(sectionY);
            for (int index = 0; index < Section.BIOME_CELLS; index++) {
                assertEquals(biomes.get((Integer) cells.get(index)), section.biomes().get(index),
                        "biome cell " + index + " of section " + sectionY);
            }
            // Cell (x=2, y=1, z=3) covers blocks x 8..11, y 4..7, z 12..15 of the section.
            assertEquals(biomes.get((Integer) cells.get(1 * 16 + 3 * 4 + 2)),
                    chunk.biome(chunk.pos().x() * 16 + 9, sectionY * 16 + 5, chunk.pos().z() * 16 + 14));
        }
    }
}
