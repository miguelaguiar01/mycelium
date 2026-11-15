package mycelium.region;

import mycelium.nbt.CompoundTag;

/** What one of the 1,024 slots of a region file holds. */
public sealed interface RegionSlot {

    /** No chunk was ever saved here. */
    record Empty() implements RegionSlot {
    }

    /**
     * A readable chunk.
     *
     * @param timestamp   the save time from the region header, in seconds since the epoch
     * @param compression how the chunk was stored
     * @param external    whether it was stored in a separate {@code .mcc} file
     * @param rootName    the name of the root tag; the game writes an empty name
     * @param root        the chunk's NBT
     */
    record Chunk(int timestamp, Compression compression, boolean external, String rootName, CompoundTag root)
            implements RegionSlot {
    }

    /**
     * The slot is allocated but its chunk cannot be read. Callers must report this and must not
     * treat it as an empty slot.
     */
    record Corrupt(int timestamp, String reason) implements RegionSlot {
    }
}
