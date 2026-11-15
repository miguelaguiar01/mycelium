package mycelium.region;

import java.io.IOException;
import java.util.Arrays;

/**
 * What one slot of a region file holds, before anything is decompressed.
 *
 * <p>Comparing two stored chunks byte for byte is far cheaper than decoding them, and a stored
 * chunk can be written to another region file as it is.
 */
public sealed interface RawSlot {

    /** No chunk was ever saved here. */
    record Empty() implements RawSlot {
    }

    /**
     * A chunk as stored: {@code length} compressed bytes of {@code data}, starting at {@code offset}.
     *
     * @param timestamp   the save time from the region header, in seconds since the epoch
     * @param compression how the bytes are compressed
     * @param external    whether they came from a separate {@code .mcc} file
     */
    record Stored(int timestamp, Compression compression, boolean external, byte[] data, int offset, int length)
            implements RawSlot {

        /** Whether the other chunk is compressed the same way and has the same bytes. */
        public boolean sameBytes(Stored other) {
            return compression == other.compression && Arrays.equals(data, offset, offset + length,
                    other.data, other.offset, other.offset + other.length);
        }

        /** The compressed bytes, copied only if they are part of a larger array. */
        public byte[] payload() {
            return offset == 0 && length == data.length ? data : Arrays.copyOfRange(data, offset, offset + length);
        }

        /** The decompressed NBT bytes. */
        public byte[] nbt() throws IOException {
            return compression.decompress(data, offset, length);
        }
    }

    /**
     * The slot is allocated but its chunk cannot be located or its compression is unknown.
     * Damage inside the compressed bytes only shows when the chunk is decoded.
     */
    record Corrupt(int timestamp, String reason) implements RawSlot {
    }

    /** The slot's save time, or 0 if it is empty. */
    default int timestampOrZero() {
        return this instanceof Stored stored ? stored.timestamp() : this instanceof Corrupt corrupt ? corrupt.timestamp() : 0;
    }
}
