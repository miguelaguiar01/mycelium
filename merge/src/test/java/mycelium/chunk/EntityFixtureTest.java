package mycelium.chunk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import mycelium.json.Json;
import mycelium.nbt.CompoundTag;
import mycelium.nbt.IntArrayTag;
import mycelium.nbt.ListTag;
import mycelium.testing.Fixtures;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.SortedMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Entity files and rider nesting against the real 26.3 fixture worlds. */
class EntityFixtureTest {

    private static UUID probe(int n) {
        return new UUID(0, n);
    }

    static List<String> worlds() {
        return Fixtures.WORLDS;
    }

    @ParameterizedTest
    @MethodSource("worlds")
    void everyEntityChunkDecodesAndEncodesBackToTheSameNbt(String world) throws ChunkFormatException {
        List<Fixtures.FixtureChunk> chunks = Fixtures.chunks(world, "entities");
        assertFalse(chunks.isEmpty());
        for (Fixtures.FixtureChunk fixture : chunks) {
            EntityChunk chunk = EntityChunkCodec.decode(fixture.root());
            assertEquals(fixture.root(), EntityChunkCodec.encode(chunk), fixture::toString);
            assertEquals(DataVersions.MC_26_3, chunk.dataVersion());
            assertTrue(chunk.extra().isEmpty());
            assertEquals(fixture.slot(),
                    mycelium.region.RegionPos.slotIndex(chunk.pos().x(), chunk.pos().z()));
            for (CompoundTag entity : chunk.entities()) {
                assertEquals(chunk.pos(), Entities.chunkOf(entity), "an entity is saved in the chunk that contains it");
            }
        }
    }

    @ParameterizedTest
    @MethodSource("worlds")
    void flatteningAndNestingRidersGivesBackTheSameEntities(String world) throws ChunkFormatException {
        for (Fixtures.FixtureChunk fixture : Fixtures.chunks(world, "entities")) {
            EntityChunk chunk = EntityChunkCodec.decode(fixture.root());
            List<FlatEntity> flat = Entities.flatten(chunk.entities());
            assertEquals(chunk.entities(), Entities.nest(flat), fixture::toString);
            for (FlatEntity entity : flat) {
                assertFalse(entity.data().contains("Passengers"), "flat entities carry no nested riders");
            }
            // Nesting does not depend on the order the flat entities arrive in.
            List<FlatEntity> shuffled = new ArrayList<>(flat);
            Collections.shuffle(shuffled, new Random(7));
            assertEquals(new HashSet<>(chunk.entities()), new HashSet<>(Entities.nest(shuffled)));
        }
    }

    @ParameterizedTest
    @MethodSource("worlds")
    void everySummonedEntityIsIndexedByUuidWithItsVehicle(String world) throws ChunkFormatException {
        EntityIndex index = new EntityIndex();
        for (Fixtures.FixtureChunk fixture : Fixtures.chunks(world, "entities")) {
            index.add(EntityChunkCodec.decode(fixture.root()));
        }
        assertTrue(index.duplicates().isEmpty());
        Map<String, Object> expected = Json.object(Fixtures.expected(world).get("entities"), "expected.json");
        assertEquals(22, expected.size());
        for (Map.Entry<String, Object> entry : expected.entrySet()) {
            Map<String, Object> wanted = Json.object(entry.getValue(), "expected.json");
            FlatEntity entity = index.get(UUID.fromString(entry.getKey()));
            assertNotNull(entity, entry.getKey() + " " + wanted.get("note"));
            assertEquals(wanted.get("id"), entity.id());
            String vehicle = (String) wanted.get("vehicle");
            assertEquals(vehicle == null ? null : UUID.fromString(vehicle), entity.vehicle(), (String) wanted.get("note"));
        }
        // Pig carries zombie carries chicken; the boat seats the villager first, then the cat.
        assertEquals(List.of(probe(2)), index.get(probe(1)).passengers());
        assertEquals(List.of(probe(3)), index.get(probe(2)).passengers());
        assertEquals(List.of(probe(5), probe(6)), index.get(probe(4)).passengers());
        // References to other entities are plain UUIDs in four ints.
        CompoundTag leash = (CompoundTag) index.get(probe(9)).data().get("leash");
        assertEquals(probe(8), Entities.toUuid(((IntArrayTag) leash.get("UUID")).ints()));
        assertEquals(new UUID(0, 999), Entities.toUuid(((IntArrayTag) index.get(probe(16)).data().get("Owner")).ints()));
        assertEquals(Entities.toTag(probe(8)), leash.get("UUID"));
    }

    @ParameterizedTest
    @MethodSource("worlds")
    void theIndexRebuildsTheSameEntityChunks(String world) throws ChunkFormatException {
        EntityIndex index = new EntityIndex();
        List<EntityChunk> original = new ArrayList<>();
        for (Fixtures.FixtureChunk fixture : Fixtures.chunks(world, "entities")) {
            EntityChunk chunk = EntityChunkCodec.decode(fixture.root());
            original.add(chunk);
            index.add(chunk);
        }
        SortedMap<ChunkPos, EntityChunk> rebuilt = index.toChunks(DataVersions.MC_26_3);
        assertEquals(original.size(), rebuilt.size());
        for (EntityChunk chunk : original) {
            EntityChunk again = rebuilt.get(chunk.pos());
            assertNotNull(again, "chunk " + chunk.pos());
            assertEquals(new HashSet<>(chunk.entities()), new HashSet<>(again.entities()));
            List<UUID> order = new ArrayList<>();
            for (CompoundTag entity : again.entities()) {
                order.add(Entities.uuidOf(entity));
            }
            assertEquals(order.stream().sorted().toList(), order, "entities come out in UUID order");
        }
    }

    @Test
    void uuidConversionRoundTrips() {
        UUID uuid = UUID.fromString("f47ac10b-58cc-4372-a567-0e02b2c3d479");
        assertEquals(uuid, Entities.toUuid(Entities.toTag(uuid).ints()));
        assertEquals(new UUID(-1, -1), Entities.toUuid(new int[] {-1, -1, -1, -1}));
        assertEquals(new UUID(0, 22), Entities.toUuid(new int[] {0, 0, 0, 22}));
    }

    @Test
    void brokenNestingIsRefused() throws ChunkFormatException {
        EntityChunk chunk = EntityChunkCodec.decode(Fixtures.chunks("probe-zlib", "entities").get(0).root());
        List<FlatEntity> flat = Entities.flatten(chunk.entities());
        List<FlatEntity> withoutChicken = flat.stream().filter(entity -> !entity.uuid().equals(probe(3))).toList();
        assertThrows(ChunkFormatException.class, () -> Entities.nest(withoutChicken), "a rider that is gone");
        List<FlatEntity> twice = new ArrayList<>(flat);
        twice.add(flat.get(0));
        assertThrows(ChunkFormatException.class, () -> Entities.nest(twice), "one UUID twice");
        CompoundTag noUuid = chunk.entities().get(0).without("UUID");
        assertThrows(ChunkFormatException.class, () -> Entities.flatten(List.of(noUuid)));
        CompoundTag badRiders = chunk.entities().get(0).with("Passengers", ListTag.of(List.of(new IntArrayTag(new int[4]))));
        assertThrows(ChunkFormatException.class, () -> Entities.flatten(List.of(badRiders)));
    }
}
