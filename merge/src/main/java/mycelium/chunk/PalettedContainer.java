package mycelium.chunk;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A fixed number of cells that each hold one value from a palette: the 4,096 block states or the
 * 64 biome cells of a section.
 *
 * <p>Instances are immutable and always canonical: the palette holds exactly the values in use,
 * ordered by first use in cell order. Two containers are therefore equal exactly when every cell
 * holds an equal value, however the data they were read from was encoded. The game writes its
 * own palettes in the same canonical order, so packing an unchanged container gives back the
 * arrays it was read from.
 *
 * <p>On disk a cell takes {@code max(minBits, ceil(log2(palette size)))} bits. Cells are packed
 * into longs from the lowest bit up and never span two longs. A palette of one value has no data
 * array at all.
 */
public final class PalettedContainer<T> {

    /** Bits per cell the game uses at least for block states. */
    public static final int MIN_BLOCK_BITS = 4;
    /** Bits per cell the game uses at least for biomes. */
    public static final int MIN_BIOME_BITS = 1;

    private final List<T> palette;
    private final short[] cells;   // palette index per cell; null when the palette has one value
    private final int size;

    private PalettedContainer(List<T> palette, short[] cells, int size) {
        this.palette = palette;
        this.cells = cells;
        this.size = size;
    }

    /** A container whose cells all hold {@code value}. */
    public static <T> PalettedContainer<T> filled(int size, T value) {
        return new PalettedContainer<>(List.of(value), null, size);
    }

    /** A container holding {@code values}, one per cell. */
    public static <T> PalettedContainer<T> of(List<T> values) {
        if (values.isEmpty() || values.size() > Short.MAX_VALUE) {
            throw new IllegalArgumentException("a container needs 1 to " + Short.MAX_VALUE + " cells");
        }
        Map<T, Integer> index = new HashMap<>();
        List<T> palette = new ArrayList<>();
        short[] cells = new short[values.size()];
        for (int i = 0; i < cells.length; i++) {
            T value = Objects.requireNonNull(values.get(i), "value");
            Integer known = index.get(value);
            if (known == null) {
                known = palette.size();
                index.put(value, known);
                palette.add(value);
            }
            cells[i] = known.shortValue();
        }
        return create(palette, cells);
    }

    /**
     * Decodes the on-disk form.
     *
     * @param palette the palette as stored, possibly with unused or repeated entries
     * @param data    the packed cells, or null when the file has no data array
     * @param size    the number of cells
     * @param minBits the minimum bits per cell for this kind of container
     */
    public static <T> PalettedContainer<T> unpack(List<T> palette, long[] data, int size, int minBits, String where)
            throws ChunkFormatException {
        if (palette.isEmpty()) {
            throw new ChunkFormatException(where + ": empty palette");
        }
        if (palette.size() == 1) {
            return filled(size, palette.get(0));   // the game ignores a data array here
        }
        if (data == null) {
            throw new ChunkFormatException(where + ": palette of " + palette.size() + " has no data array");
        }
        int bits = bitsFor(palette.size(), minBits);
        int perLong = 64 / bits;
        if (data.length != longsFor(size, bits)) {
            throw new ChunkFormatException(where + ": data array has " + data.length + " longs, expected "
                    + longsFor(size, bits) + " for a palette of " + palette.size());
        }
        long mask = (1L << bits) - 1;
        // Map stored palette indexes to canonical ones: first use in cell order, equal values merged.
        int[] remap = new int[palette.size()];
        Arrays.fill(remap, -1);
        Map<T, Integer> canonicalIndex = new HashMap<>();
        List<T> canonical = new ArrayList<>();
        short[] cells = new short[size];
        int cell = 0;
        for (long packed : data) {
            for (int n = 0; n < perLong && cell < size; n++, cell++) {
                int stored = (int) (packed >>> (n * bits) & mask);
                if (stored >= remap.length) {
                    throw new ChunkFormatException(where + ": cell " + cell + " points at palette entry " + stored
                            + " of " + remap.length);
                }
                if (remap[stored] < 0) {
                    T value = palette.get(stored);
                    Integer known = canonicalIndex.get(value);
                    if (known == null) {
                        known = canonical.size();
                        canonicalIndex.put(value, known);
                        canonical.add(value);
                    }
                    remap[stored] = known;
                }
                cells[cell] = (short) remap[stored];
            }
        }
        return create(canonical, cells);
    }

    private static <T> PalettedContainer<T> create(List<T> palette, short[] cells) {
        if (palette.size() == 1) {
            return new PalettedContainer<>(List.copyOf(palette), null, cells.length);
        }
        return new PalettedContainer<>(List.copyOf(palette), cells, cells.length);
    }

    /** The number of cells. */
    public int size() {
        return size;
    }

    /** The value in cell {@code index}. */
    public T get(int index) {
        Objects.checkIndex(index, size);
        return palette.get(cells == null ? 0 : cells[index]);
    }

    /** The distinct values in use, ordered by first use. Unmodifiable. */
    public List<T> palette() {
        return palette;
    }

    /** Whether every cell holds the same value. */
    public boolean isUniform() {
        return cells == null;
    }

    /** All cell values in order. */
    public List<T> toList() {
        List<T> values = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            values.add(get(i));
        }
        return values;
    }

    /** The packed data array for the on-disk form, or null when the palette has one value. */
    public long[] pack(int minBits) {
        if (cells == null) {
            return null;
        }
        int bits = bitsFor(palette.size(), minBits);
        int perLong = 64 / bits;
        long[] data = new long[longsFor(size, bits)];
        for (int cell = 0; cell < size; cell++) {
            data[cell / perLong] |= (long) cells[cell] << (cell % perLong * bits);
        }
        return data;
    }

    /** Bits per cell on disk for a palette of {@code paletteSize} values. */
    public static int bitsFor(int paletteSize, int minBits) {
        return Math.max(minBits, 32 - Integer.numberOfLeadingZeros(paletteSize - 1));
    }

    /** Length of the data array for {@code size} cells of {@code bits} bits. */
    public static int longsFor(int size, int bits) {
        int perLong = 64 / bits;
        return (size + perLong - 1) / perLong;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof PalettedContainer<?> that && size == that.size
                && palette.equals(that.palette) && Arrays.equals(cells, that.cells);
    }

    @Override
    public int hashCode() {
        return 31 * palette.hashCode() + Arrays.hashCode(cells);
    }

    @Override
    public String toString() {
        return "PalettedContainer[" + size + " cells, palette " + palette + "]";
    }
}
