package mycelium.chunk;

import mycelium.nbt.CompoundTag;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One entity taken out of the rider nesting of an entity file or of a player's vehicle.
 *
 * @param uuid       the entity's UUID
 * @param vehicle    the UUID of the entity it rides, or null
 * @param passengers the UUIDs of its riders, in seat order
 * @param data       the entity's NBT without its {@code Passengers} list
 * @param carrier    the UUID of the player whose file holds this entity instead of the entity
 *                   files, or null. A player who leaves the game while riding takes the vehicle
 *                   along; only the vehicle at the bottom of the stack has a carrier
 */
public record FlatEntity(UUID uuid, UUID vehicle, List<UUID> passengers, CompoundTag data, UUID carrier) {

    public FlatEntity {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(data, "data");
        passengers = List.copyOf(passengers);
    }

    /** An entity of the entity files. */
    public FlatEntity(UUID uuid, UUID vehicle, List<UUID> passengers, CompoundTag data) {
        this(uuid, vehicle, passengers, data, null);
    }

    /** The same entity as held by a player's file, or by none if {@code carrier} is null. */
    public FlatEntity carriedBy(UUID carrier) {
        return new FlatEntity(uuid, vehicle, passengers, data, carrier);
    }

    /** The entity type id, such as {@code minecraft:pig}. */
    public String id() throws ChunkFormatException {
        return Fields.requiredString(data, "id", "entity " + uuid);
    }

    /** The chunk that contains the entity's position. */
    public ChunkPos chunk() throws ChunkFormatException {
        return Entities.chunkOf(data);
    }
}
