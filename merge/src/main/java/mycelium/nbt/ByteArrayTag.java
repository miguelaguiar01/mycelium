package mycelium.nbt;

import java.util.Arrays;

/** A byte array. The array passed in and the one returned by {@link #bytes()} must not be modified. */
public final class ByteArrayTag implements Tag {

    private final byte[] value;

    public ByteArrayTag(byte[] value) {
        this.value = value;
    }

    /** The backing array, shared for speed. Treat it as read-only. */
    public byte[] bytes() {
        return value;
    }

    public int size() {
        return value.length;
    }

    @Override
    public TagType type() {
        return TagType.BYTE_ARRAY;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ByteArrayTag that && Arrays.equals(value, that.value);
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
