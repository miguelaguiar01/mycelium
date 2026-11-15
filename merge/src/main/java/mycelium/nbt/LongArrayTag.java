package mycelium.nbt;

import java.util.Arrays;

/** A long array. The array passed in and the one returned by {@link #longs()} must not be modified. */
public final class LongArrayTag implements Tag {

    private final long[] value;

    public LongArrayTag(long[] value) {
        this.value = value;
    }

    /** The backing array, shared for speed. Treat it as read-only. */
    public long[] longs() {
        return value;
    }

    public int size() {
        return value.length;
    }

    @Override
    public TagType type() {
        return TagType.LONG_ARRAY;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof LongArrayTag that && Arrays.equals(value, that.value);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(value);
    }

    @Override
    public String toString() {
        return Snbt.format(this);
    }
}
