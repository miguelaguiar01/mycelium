package mycelium.nbt;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

class NbtIoTest {

    private static CompoundTag everyType() {
        return CompoundTag.builder()
                .put("byte", new ByteTag((byte) -128))
                .put("short", new ShortTag((short) -32768))
                .put("int", new IntTag(Integer.MIN_VALUE))
                .put("long", new LongTag(Long.MIN_VALUE))
                .put("float", new FloatTag(-0.0f))
                .put("double", new DoubleTag(Double.MIN_VALUE))
                .put("bytes", new ByteArrayTag(new byte[] {1, -2, 3}))
                .put("string", new StringTag("text"))
                .put("list", ListTag.of(List.of(new IntTag(1), new IntTag(2))))
                .put("empty list", ListTag.empty())
                .put("nested", CompoundTag.of("inner", ListTag.of(List.of(CompoundTag.empty()))))
                .put("ints", new IntArrayTag(new int[] {Integer.MAX_VALUE, -1}))
                .put("longs", new LongArrayTag(new long[] {Long.MAX_VALUE, -1}))
                .put("", new StringTag("empty key"))
                .build();
    }

    @Test
    void everyTagTypeRoundTrips() throws IOException {
        NamedTag root = new NamedTag("root name", everyType());
        byte[] bytes = NbtIo.write(root);
        NamedTag read = NbtIo.read(bytes);
        assertEquals(root, read);
        assertArrayEquals(bytes, NbtIo.write(read), "writing what was read gives the same bytes");
    }

    @Test
    void compoundKeepsKeyOrderButComparesWithoutIt() throws IOException {
        CompoundTag ab = CompoundTag.builder().put("a", new IntTag(1)).put("b", new IntTag(2)).build();
        CompoundTag ba = CompoundTag.builder().put("b", new IntTag(2)).put("a", new IntTag(1)).build();
        assertEquals(ab, ba);
        assertEquals(ab.hashCode(), ba.hashCode());
        assertEquals(List.of("b", "a"), List.copyOf(((CompoundTag) NbtIo.read(NbtIo.write(NamedTag.of(ba))).tag()).keys()));
        assertArrayEquals(NbtIo.write(NamedTag.of(ab), NbtIo.KeyOrder.SORTED),
                NbtIo.write(NamedTag.of(ba), NbtIo.KeyOrder.SORTED), "sorted output does not depend on key order");
        assertNotEquals(ab, ab.with("a", new IntTag(3)));
        assertNotEquals(ab, ab.with("a", new LongTag(1)), "a value of another type is another value");
    }

    @Test
    void emptyListKeepsItsDeclaredTypeForWritingOnly() throws IOException {
        ListTag typed = ListTag.of(TagType.COMPOUND, List.of());
        assertEquals(ListTag.empty(), typed, "the game treats all empty lists alike");
        byte[] bytes = NbtIo.write(NamedTag.of(CompoundTag.of("l", typed)));
        ListTag read = (ListTag) ((CompoundTag) NbtIo.read(bytes).tag()).get("l");
        assertEquals(TagType.COMPOUND, read.elementType());
        assertArrayEquals(bytes, NbtIo.write(NamedTag.of(CompoundTag.of("l", read))));
    }

    @Test
    void stringsUseModifiedUtf8() throws IOException {
        String text = "nul:\u0000 bmp:é世 astral:🍄 lone surrogate:\ud83c";
        NamedTag root = NamedTag.of(CompoundTag.of(text, new StringTag(text)));
        byte[] bytes = NbtIo.write(root);
        assertEquals(root, NbtIo.read(bytes));
        // Modified UTF-8 never contains a zero byte inside a string and writes astral characters as two
        // three-byte surrogates. Check against Java's own encoder, which is what the game uses.
        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        new DataOutputStream(expected).writeUTF(text);
        byte[] encoded = expected.toByteArray();
        assertArrayEquals(encoded, java.util.Arrays.copyOfRange(bytes, 4, 4 + encoded.length));
    }

    @Test
    void floatBitsSurviveIncludingNan() throws IOException {
        float nan = Float.intBitsToFloat(0x7fc12345);
        double negativeZero = -0.0;
        CompoundTag tag = CompoundTag.builder().put("f", new FloatTag(nan)).put("d", new DoubleTag(negativeZero)).build();
        byte[] bytes = NbtIo.write(NamedTag.of(tag));
        assertArrayEquals(bytes, NbtIo.write(NbtIo.read(bytes)));
        assertNotEquals(new DoubleTag(0.0), new DoubleTag(-0.0));
    }

    @Test
    void trailingBytesAreIgnoredLikeTheGameDoes() throws IOException {
        byte[] bytes = NbtIo.write(NamedTag.of(everyType()));
        byte[] padded = java.util.Arrays.copyOf(bytes, bytes.length + 7);
        assertEquals(NbtIo.read(bytes), NbtIo.read(padded));
    }

    @Test
    void truncatedInputIsRejectedAtEveryLength() {
        byte[] bytes = NbtIo.write(NamedTag.of(everyType()));
        for (int length = 0; length < bytes.length; length++) {
            byte[] cut = java.util.Arrays.copyOf(bytes, length);
            assertThrows(NbtFormatException.class, () -> NbtIo.read(cut), "length " + length);
        }
    }

    @Test
    void corruptLengthsAreRejectedWithoutAllocating() {
        // Root compound holding a long array that claims 2^31-1 elements.
        byte[] longArray = {10, 0, 0, 12, 0, 1, 'a', 0x7f, (byte) 0xff, (byte) 0xff, (byte) 0xff, 0};
        assertThrows(NbtFormatException.class, () -> NbtIo.read(longArray));
        byte[] negativeList = {10, 0, 0, 9, 0, 1, 'a', 3, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, 0};
        assertThrows(NbtFormatException.class, () -> NbtIo.read(negativeList));
        byte[] endListWithElements = {10, 0, 0, 9, 0, 1, 'a', 0, 0, 0, 0, 1, 0};
        assertThrows(NbtFormatException.class, () -> NbtIo.read(endListWithElements));
        byte[] unknownType = {10, 0, 0, 13, 0, 1, 'a', 0};
        assertThrows(NbtFormatException.class, () -> NbtIo.read(unknownType));
        byte[] endRoot = {0};
        assertThrows(NbtFormatException.class, () -> NbtIo.read(endRoot));
    }

    @Test
    void aSmallFileCannotUnfoldIntoGigabytes() throws IOException {
        // 500 lists nested in each other, each declaring four million elements, then 4 MiB of zeros.
        // Reserving every declared length would take about 8 GB.
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.write(new byte[] {10, 0, 0, 9, 0, 1, 'a'});
        for (int level = 0; level < 500; level++) {
            out.writeByte(9);
            out.writeInt(4 << 20);
        }
        out.write(new byte[4 << 20]);
        assertThrows(NbtFormatException.class, () -> NbtIo.read(bytes.toByteArray()));

        // A list of single bytes costs an object per element; the tag limit bounds that.
        ByteArrayOutputStream many = new ByteArrayOutputStream();
        DataOutputStream manyOut = new DataOutputStream(many);
        manyOut.write(new byte[] {10, 0, 0, 9, 0, 1, 'a', 1});
        manyOut.writeInt(NbtIo.MAX_TAGS);
        manyOut.write(new byte[NbtIo.MAX_TAGS]);
        manyOut.writeByte(0);
        NbtFormatException tooMany = assertThrows(NbtFormatException.class, () -> NbtIo.read(many.toByteArray()));
        assertEquals("NBT holds more than " + NbtIo.MAX_TAGS + " tags", tooMany.getMessage());
    }

    @Test
    void nestingDeeperThanTheGameAllowsIsRejected() throws IOException {
        Tag deep = new IntTag(1);
        for (int i = 0; i < NbtIo.MAX_DEPTH; i++) {
            deep = ListTag.of(List.of(deep));
        }
        NamedTag atLimit = NamedTag.of(deep);
        assertEquals(atLimit, NbtIo.read(NbtIo.write(atLimit)));
        byte[] tooDeep = NbtIo.write(NamedTag.of(ListTag.of(List.of(deep))));
        assertThrows(NbtFormatException.class, () -> NbtIo.read(tooDeep));
    }

    @Test
    void listsRejectMixedElements() {
        assertThrows(IllegalArgumentException.class, () -> ListTag.of(List.of(new IntTag(1), new LongTag(2))));
    }

    @Test
    void mixedListsAreWrappedLikeTheGameDoes() {
        StringTag air = new StringTag("minecraft:air");
        CompoundTag sign = CompoundTag.of("id", new StringTag("minecraft:oak_sign"));
        CompoundTag lookalike = CompoundTag.of("", new IntTag(7));

        ListTag strings = ListTag.mixed(List.of(air, air));
        assertEquals(TagType.STRING, strings.elementType(), "one type needs no wrapping");
        assertEquals(List.of(air, air), strings.elements());

        ListTag mixed = ListTag.mixed(List.of(air, sign));
        assertEquals(TagType.COMPOUND, mixed.elementType());
        assertEquals(List.of(CompoundTag.of("", air), sign), mixed.elements());
        assertEquals(List.of(air, sign), mixed.logical());

        ListTag compounds = ListTag.mixed(List.of(sign, lookalike));
        assertEquals(List.of(sign, CompoundTag.of("", lookalike)), compounds.elements(),
                "a compound that looks like a wrapper is wrapped so it survives unwrapping");
        assertEquals(List.of(sign, lookalike), compounds.logical());
        assertEquals(ListTag.empty(), ListTag.mixed(List.of()));
    }

    @Test
    void snbtIsReadable() {
        assertEquals("{a: 1b, b: [1, 2], c: \"x\\\"y\", \"d e\": [L; 5L]}", Snbt.format(CompoundTag.builder()
                .put("b", ListTag.of(List.of(new IntTag(1), new IntTag(2))))
                .put("a", new ByteTag((byte) 1))
                .put("d e", new LongArrayTag(new long[] {5}))
                .put("c", new StringTag("x\"y"))
                .build()));
    }
}
