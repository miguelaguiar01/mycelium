package mycelium.chunk;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PalettedContainerTest {

    /** Palette size, bits per cell and data array length, as measured on real 26.3 sections. */
    @ParameterizedTest
    @CsvSource({
        "2, 4, 256", "16, 4, 256", "17, 5, 342", "33, 6, 410", "65, 7, 456", "129, 8, 512",
        "257, 9, 586", "513, 10, 683", "1025, 11, 820", "2049, 12, 820", "4096, 12, 820",
    })
    void blockPackingMatchesTheGame(int paletteSize, int bits, int longs) {
        assertEquals(bits, PalettedContainer.bitsFor(paletteSize, PalettedContainer.MIN_BLOCK_BITS));
        assertEquals(longs, PalettedContainer.longsFor(4096, bits));
    }

    @ParameterizedTest
    @CsvSource({"2, 1, 1", "3, 2, 2", "5, 3, 4", "9, 4, 4", "17, 5, 6", "33, 6, 7", "64, 6, 7"})
    void biomePackingMatchesTheGame(int paletteSize, int bits, int longs) {
        assertEquals(bits, PalettedContainer.bitsFor(paletteSize, PalettedContainer.MIN_BIOME_BITS));
        assertEquals(longs, PalettedContainer.longsFor(64, bits));
    }

    @Test
    void cellsFillEachLongFromTheLowestBitAndNeverSpanTwoLongs() throws ChunkFormatException {
        // 5 bits per cell: 12 cells per long, the top 4 bits of every long unused.
        List<Integer> values = new ArrayList<>();
        for (int i = 0; i < 4096; i++) {
            values.add(i % 17);
        }
        PalettedContainer<Integer> container = PalettedContainer.of(values);
        long[] data = container.pack(4);
        assertEquals(342, data.length);
        long expectedFirst = 0;
        for (int cell = 0; cell < 12; cell++) {
            expectedFirst |= (long) cell << (cell * 5);
        }
        assertEquals(expectedFirst, data[0]);
        for (long packed : data) {
            assertEquals(0, packed >>> 60, "padding bits are zero");
        }
        assertEquals(12L, data[1] & 31, "cell 12 starts the second long");
        assertEquals(container, PalettedContainer.unpack(container.palette(), data, 4096, 4, "test"));
    }

    @Test
    void uniformContainerHasNoData() throws ChunkFormatException {
        PalettedContainer<String> air = PalettedContainer.filled(4096, "air");
        assertNull(air.pack(4));
        assertTrue(air.isUniform());
        assertEquals("air", air.get(4095));
        assertEquals(air, PalettedContainer.of(Collections.nCopies(4096, "air")));
        assertEquals(air, PalettedContainer.unpack(List.of("air"), null, 4096, 4, "test"));
        assertEquals(air, PalettedContainer.unpack(List.of("air"), new long[256], 4096, 4, "test"),
                "the game ignores a data array next to a single-entry palette");
        assertEquals(air, PalettedContainer.unpack(List.of("air", "stone"), new long[256], 4096, 4, "test"),
                "an unused palette entry does not make the container different");
    }

    @Test
    void equalityIsByCellValueNotByEncoding() throws ChunkFormatException {
        // Same cells, written with the palette in another order, an unused entry and a repeated one.
        long[] data = new long[256];
        long[] other = new long[256];
        for (int cell = 0; cell < 4096; cell++) {
            int value = cell % 3;                       // a, b, c, a, b, c ...
            data[cell / 16] |= (long) value << (cell % 16 * 4);
            int remapped = new int[] {4, 2, 0}[value];  // palette [c, unused, b, unused, a]
            other[cell / 16] |= (long) remapped << (cell % 16 * 4);
        }
        PalettedContainer<String> canonical = PalettedContainer.unpack(List.of("a", "b", "c"), data, 4096, 4, "test");
        PalettedContainer<String> scrambled =
                PalettedContainer.unpack(List.of("c", "unused", "b", "c", "a"), other, 4096, 4, "test");
        assertEquals(canonical, scrambled);
        assertEquals(canonical.hashCode(), scrambled.hashCode());
        assertEquals(List.of("a", "b", "c"), scrambled.palette(), "palette is rebuilt in first-use order");
        assertArrayEquals(data, scrambled.pack(4));

        List<String> changed = canonical.toList();
        changed.set(4095, "d");
        assertNotEquals(canonical, PalettedContainer.of(changed));
    }

    @Test
    void malformedDataIsRejected() {
        assertThrows(ChunkFormatException.class, () -> PalettedContainer.unpack(List.of(), null, 4096, 4, "test"));
        assertThrows(ChunkFormatException.class, () -> PalettedContainer.unpack(List.of("a", "b"), null, 4096, 4, "test"));
        assertThrows(ChunkFormatException.class,
                () -> PalettedContainer.unpack(List.of("a", "b"), new long[255], 4096, 4, "test"));
        assertThrows(ChunkFormatException.class,
                () -> PalettedContainer.unpack(List.of("a", "b"), new long[342], 4096, 4, "test"),
                "a data array sized for more bits than the palette needs is not guessed at");
        long[] outOfRange = new long[256];
        outOfRange[100] = 2;
        assertThrows(ChunkFormatException.class,
                () -> PalettedContainer.unpack(List.of("a", "b"), outOfRange, 4096, 4, "test"));
    }

    @Property(tries = 300)
    void packThenUnpackGivesTheSameCells(@ForAll long seed, @ForAll @IntRange(min = 1, max = 4096) int distinct,
                                         @ForAll boolean biomes) throws ChunkFormatException {
        int size = biomes ? 64 : 4096;
        int minBits = biomes ? 1 : 4;
        Random random = new Random(seed);
        int paletteSize = Math.min(distinct, size);
        List<Integer> values = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            values.add(random.nextInt(paletteSize));
        }
        PalettedContainer<Integer> container = PalettedContainer.of(values);
        assertEquals(values, container.toList());

        // Canonical form: distinct values, all in use, ordered by first use.
        List<Integer> firstUse = new ArrayList<>(new java.util.LinkedHashSet<>(values));
        assertEquals(firstUse, container.palette());

        long[] data = container.pack(minBits);
        if (firstUse.size() == 1) {
            assertNull(data);
        } else {
            int bits = PalettedContainer.bitsFor(firstUse.size(), minBits);
            assertEquals(PalettedContainer.longsFor(size, bits), data.length);
        }
        PalettedContainer<Integer> read = PalettedContainer.unpack(container.palette(), data, size, minBits, "test");
        assertEquals(container, read);
        assertEquals(values, read.toList());

        // A shuffled palette with remapped cells is the same container.
        List<Integer> shuffled = new ArrayList<>(container.palette());
        Collections.shuffle(shuffled, random);
        if (shuffled.size() > 1) {
            int bits = PalettedContainer.bitsFor(shuffled.size(), minBits);
            int perLong = 64 / bits;
            long[] remapped = new long[data.length];
            for (int cell = 0; cell < size; cell++) {
                remapped[cell / perLong] |= (long) shuffled.indexOf(values.get(cell)) << (cell % perLong * bits);
            }
            assertEquals(container, PalettedContainer.unpack(shuffled, remapped, size, minBits, "test"));
        }
        assertEquals(new HashSet<>(values).size(), container.palette().size());
    }
}
