package mycelium.chunk;

import mycelium.nbt.CompoundTag;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Every entity of one dimension, riders included, keyed by UUID.
 *
 * <p>Built from entity chunks with {@link #add}; {@link #toChunks} turns it back into chunks,
 * placing each vehicle in the chunk that contains its position and its riders inside it.
 * Iteration is in UUID order, so results do not depend on the order chunks were added in.
 */
public final class EntityIndex {

    private final SortedMap<UUID, FlatEntity> entities = new TreeMap<>();
    private final List<UUID> duplicates = new ArrayList<>();

    /** An index of entities whose vehicle and passenger links are already consistent. */
    public static EntityIndex of(Collection<FlatEntity> entities) {
        EntityIndex index = new EntityIndex();
        for (FlatEntity entity : entities) {
            if (index.entities.putIfAbsent(entity.uuid(), entity) != null) {
                index.duplicates.add(entity.uuid());
            }
        }
        return index;
    }

    /** Adds the entities of one chunk. A UUID already present is kept as it was and recorded. */
    public void add(EntityChunk chunk) throws ChunkFormatException {
        for (FlatEntity entity : Entities.flatten(chunk.entities())) {
            if (entities.putIfAbsent(entity.uuid(), entity) != null) {
                duplicates.add(entity.uuid());
            }
        }
    }

    /** All entities in UUID order. Unmodifiable. */
    public SortedMap<UUID, FlatEntity> entities() {
        return Collections.unmodifiableSortedMap(entities);
    }

    public FlatEntity get(UUID uuid) {
        return entities.get(uuid);
    }

    /** UUIDs that were added more than once; only the first occurrence is in the index. */
    public List<UUID> duplicates() {
        return Collections.unmodifiableList(duplicates);
    }

    /**
     * Rebuilds entity chunks, in chunk order, with entities in UUID order inside each. A vehicle
     * that a player's file holds is left out with its riders; see {@link #carried}.
     */
    public SortedMap<ChunkPos, EntityChunk> toChunks(int dataVersion) throws ChunkFormatException {
        SortedMap<ChunkPos, List<FlatEntity>> byChunk = new TreeMap<>();
        for (FlatEntity entity : entities.values()) {
            FlatEntity root = root(entity);
            if (root.carrier() == null) {
                byChunk.computeIfAbsent(root.chunk(), pos -> new ArrayList<>()).add(entity);
            }
        }
        SortedMap<ChunkPos, EntityChunk> chunks = new TreeMap<>();
        for (Map.Entry<ChunkPos, List<FlatEntity>> entry : byChunk.entrySet()) {
            List<CompoundTag> nested = Entities.nest(entry.getValue());
            chunks.put(entry.getKey(), new EntityChunk(dataVersion, entry.getKey(), nested, CompoundTag.empty()));
        }
        return chunks;
    }

    /**
     * The entities that players' files hold, by player: a vehicle the player left the game in, a
     * parrot on a shoulder. Each has its riders nested inside it; a player's are in UUID order.
     */
    public SortedMap<UUID, List<CompoundTag>> carried() throws ChunkFormatException {
        SortedMap<UUID, List<FlatEntity>> byCarrier = new TreeMap<>();
        for (FlatEntity entity : entities.values()) {
            FlatEntity root = root(entity);
            if (root.carrier() != null) {
                byCarrier.computeIfAbsent(root.carrier(), player -> new ArrayList<>()).add(entity);
            }
        }
        SortedMap<UUID, List<CompoundTag>> held = new TreeMap<>();
        for (Map.Entry<UUID, List<FlatEntity>> entry : byCarrier.entrySet()) {
            held.put(entry.getKey(), Entities.nest(entry.getValue()));
        }
        return held;
    }

    /** The vehicle at the bottom of the stack an entity rides in; the entity itself if it rides nothing. */
    private FlatEntity root(FlatEntity entity) throws ChunkFormatException {
        FlatEntity root = entity;
        for (int depth = 0; root.vehicle() != null; depth++) {
            FlatEntity vehicle = entities.get(root.vehicle());
            if (vehicle == null || depth > entities.size()) {
                throw new ChunkFormatException("vehicle " + root.vehicle() + " of entity " + root.uuid() + " is missing");
            }
            root = vehicle;
        }
        return root;
    }
}
