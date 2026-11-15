package mycelium.chunk;

import mycelium.nbt.CompoundTag;
import mycelium.nbt.DoubleTag;
import mycelium.nbt.IntArrayTag;
import mycelium.nbt.ListTag;
import mycelium.nbt.Tag;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Entity NBT helpers. In an entity file a rider is stored inside its vehicle, in the vehicle's
 * {@code Passengers} list, to any depth. {@link #flatten} takes that nesting apart so entities
 * can be handled one by one by UUID, and {@link #nest} puts it back.
 */
public final class Entities {

    private static final String UUID_KEY = "UUID";
    private static final String POS = "Pos";
    private static final String PASSENGERS = "Passengers";
    /** The world border cannot be set further out than this; NaN and infinities fail the comparison too. */
    private static final double WORLD_LIMIT = 30_000_000;

    private Entities() {
    }

    /** The entity's UUID, stored as four ints from the most significant down. */
    public static UUID uuidOf(CompoundTag entity) throws ChunkFormatException {
        return toUuid(Fields.requiredInts(entity, UUID_KEY, 4, "entity"));
    }

    /** Converts the four-int form used for every UUID in entity NBT. */
    public static UUID toUuid(int[] ints) {
        return new UUID((long) ints[0] << 32 | ints[1] & 0xFFFFFFFFL, (long) ints[2] << 32 | ints[3] & 0xFFFFFFFFL);
    }

    public static IntArrayTag toTag(UUID uuid) {
        long most = uuid.getMostSignificantBits();
        long least = uuid.getLeastSignificantBits();
        return new IntArrayTag(new int[] {(int) (most >> 32), (int) most, (int) (least >> 32), (int) least});
    }

    /** The entity's position: three doubles. */
    public static double[] positionOf(CompoundTag entity) throws ChunkFormatException {
        ListTag pos = Fields.required(entity, POS, ListTag.class, "entity");
        if (pos.size() != 3) {
            throw new ChunkFormatException("entity: Pos has " + pos.size() + " elements");
        }
        double[] position = new double[3];
        for (int i = 0; i < 3; i++) {
            if (!(pos.get(i) instanceof DoubleTag value)) {
                throw new ChunkFormatException("entity: Pos holds a " + pos.get(i).type());
            }
            position[i] = value.value();
        }
        return position;
    }

    /** The chunk that contains the entity's position, which is the chunk the game saves it in. */
    public static ChunkPos chunkOf(CompoundTag entity) throws ChunkFormatException {
        double[] position = positionOf(entity);
        return new ChunkPos((int) Math.floor(position[0]) >> 4, (int) Math.floor(position[2]) >> 4);
    }

    /**
     * Takes rider nesting apart. Each vehicle comes before its riders, and riders keep their seat
     * order.
     */
    public static List<FlatEntity> flatten(List<CompoundTag> topLevel) throws ChunkFormatException {
        List<FlatEntity> flat = new ArrayList<>();
        for (CompoundTag entity : topLevel) {
            // The position decides the chunk an entity is written to, so it is checked where it is read.
            double[] position = positionOf(entity);
            for (double coordinate : position) {
                if (!(Math.abs(coordinate) <= WORLD_LIMIT)) {
                    throw new ChunkFormatException("entity " + uuidOf(entity) + ": Pos " + java.util.Arrays.toString(position)
                            + " is outside the world");
                }
            }
            flatten(entity, null, flat);
        }
        return flat;
    }

    private static UUID flatten(CompoundTag entity, UUID vehicle, List<FlatEntity> out) throws ChunkFormatException {
        UUID uuid = uuidOf(entity);
        int slot = out.size();
        out.add(null);   // keep the vehicle ahead of its riders
        List<UUID> passengers = new ArrayList<>();
        ListTag riders = Fields.optional(entity, PASSENGERS, ListTag.class, "entity " + uuid);
        if (riders != null) {
            for (Tag rider : riders) {
                passengers.add(flatten(Fields.compound(rider, "entity " + uuid + " " + PASSENGERS), uuid, out));
            }
        }
        out.set(slot, new FlatEntity(uuid, vehicle, passengers, entity.without(PASSENGERS)));
        return uuid;
    }

    /**
     * Rebuilds rider nesting. Returns the entities that ride nothing, in the order given, each
     * with its riders nested inside it.
     *
     * @throws ChunkFormatException if a UUID occurs twice or a rider is missing
     */
    public static List<CompoundTag> nest(List<FlatEntity> entities) throws ChunkFormatException {
        Map<UUID, FlatEntity> byUuid = new HashMap<>();
        for (FlatEntity entity : entities) {
            if (byUuid.put(entity.uuid(), entity) != null) {
                throw new ChunkFormatException("entity " + entity.uuid() + " occurs twice");
            }
        }
        List<CompoundTag> topLevel = new ArrayList<>();
        for (FlatEntity entity : entities) {
            if (entity.vehicle() == null) {
                topLevel.add(nest(entity, byUuid, 0));
            }
        }
        return topLevel;
    }

    private static CompoundTag nest(FlatEntity entity, Map<UUID, FlatEntity> byUuid, int depth)
            throws ChunkFormatException {
        if (entity.passengers().isEmpty()) {
            return entity.data();
        }
        if (depth > byUuid.size()) {
            throw new ChunkFormatException("entity " + entity.uuid() + " rides itself");
        }
        List<CompoundTag> riders = new ArrayList<>();
        for (UUID passenger : entity.passengers()) {
            FlatEntity rider = byUuid.get(passenger);
            if (rider == null) {
                throw new ChunkFormatException("rider " + passenger + " of entity " + entity.uuid() + " is missing");
            }
            riders.add(nest(rider, byUuid, depth + 1));
        }
        return entity.data().with(PASSENGERS, ListTag.of(riders));
    }
}
