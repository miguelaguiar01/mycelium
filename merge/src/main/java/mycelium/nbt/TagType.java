package mycelium.nbt;

/** The twelve binary NBT tag types plus the END marker, with their ids on disk. */
public enum TagType {
    END, BYTE, SHORT, INT, LONG, FLOAT, DOUBLE, BYTE_ARRAY, STRING, LIST, COMPOUND, INT_ARRAY, LONG_ARRAY;

    private static final TagType[] VALUES = values();

    /** The id written to disk. */
    public int id() {
        return ordinal();
    }

    public static TagType fromId(int id) throws NbtFormatException {
        if (id < 0 || id >= VALUES.length) {
            throw new NbtFormatException("unknown tag type id " + id);
        }
        return VALUES[id];
    }
}
