package mycelium.nbt;

import java.util.Arrays;

/** A int array. The array passed in and the one returned by {@link #ints()} must not be modified. */
public final class IntArrayTag implements Tag {

    private final int[] value;

    public IntArrayTag(int[] value) {
        this.value = value;
    }

    /** The backing array, shared for speed. Treat it as read-only. */
    public int[] ints() {
        return value;
    }

    public int size() {
        return value.length;
    }

    @Override
    public TagType type() {
        return TagType.INT_ARRAY;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof IntArrayTag that && Arrays.equals(value, that.value);
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
