package mycelium.chunk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import mycelium.nbt.CompoundTag;
import mycelium.nbt.IntTag;
import mycelium.nbt.StringTag;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BlockStateTest {

    private static final CompoundTag SIGN = CompoundTag.builder()
            .put("id", new StringTag("minecraft:oak_sign"))
            .put("properties", CompoundTag.builder()
                    .put("waterlogged", new StringTag("false"))
                    .put("rotation", new StringTag("4"))
                    .build())
            .build();

    @Test
    void decodesBothFormsThatTheGameWrites() throws ChunkFormatException {
        BlockState chest = BlockState.fromTag(new StringTag("minecraft:chest"), "test");
        assertEquals(BlockState.of("minecraft:chest"), chest);
        assertTrue(chest.isDefault());

        BlockState sign = BlockState.fromTag(SIGN, "test");
        assertEquals("minecraft:oak_sign", sign.name());
        assertEquals(List.of("rotation", "waterlogged"), List.copyOf(sign.properties().keySet()), "properties are sorted");
        assertFalse(sign.isDefault());
        assertEquals("minecraft:oak_sign[rotation=4,waterlogged=false]", sign.toString());
    }

    @Test
    void unwrapsTheMixedListWrapper() throws ChunkFormatException {
        assertEquals(BlockState.of("minecraft:air"),
                BlockState.fromTag(CompoundTag.of("", new StringTag("minecraft:air")), "test"));
    }

    @Test
    void encodesAsTheGameDoes() throws ChunkFormatException {
        assertEquals(new StringTag("minecraft:chest"), BlockState.of("minecraft:chest").toTag());
        assertEquals(SIGN, BlockState.of("minecraft:oak_sign", Map.of("rotation", "4", "waterlogged", "false")).toTag());
        assertEquals(BlockState.fromTag(SIGN, "test"), BlockState.fromTag(BlockState.fromTag(SIGN, "test").toTag(), "test"));
    }

    @Test
    void aCompoundWithoutPropertiesIsTheDefaultState() throws ChunkFormatException {
        BlockState bare = BlockState.fromTag(CompoundTag.of("id", new StringTag("minecraft:stone")), "test");
        assertEquals(BlockState.of("minecraft:stone"), bare);
        assertEquals(new StringTag("minecraft:stone"), bare.toTag());
    }

    @Test
    void equalityIsByNameAndProperties() {
        assertEquals(BlockState.parse("minecraft:oak_door[half=upper,open=false]"),
                BlockState.of("minecraft:oak_door", Map.of("open", "false", "half", "upper")));
        assertNotEquals(BlockState.parse("minecraft:oak_door[half=upper]"), BlockState.parse("minecraft:oak_door[half=lower]"));
        assertNotEquals(BlockState.of("minecraft:oak_door"), BlockState.of("minecraft:spruce_door"));
        assertEquals(BlockState.of("minecraft:stone"), BlockState.parse("minecraft:stone"));
    }

    @Test
    void unknownShapesAreRejectedNotGuessedAt() {
        assertThrows(ChunkFormatException.class, () -> BlockState.fromTag(new IntTag(1), "test"));
        assertThrows(ChunkFormatException.class,
                () -> BlockState.fromTag(CompoundTag.of("Name", new StringTag("minecraft:stone")), "test"),
                "the pre-26 layout is not accepted");
        assertThrows(ChunkFormatException.class, () -> BlockState.fromTag(SIGN.with("extra", new IntTag(1)), "test"));
        assertThrows(ChunkFormatException.class, () -> BlockState.fromTag(
                SIGN.with("properties", CompoundTag.of("rotation", new IntTag(4))), "test"));
        assertThrows(IllegalArgumentException.class, () -> BlockState.parse("minecraft:stone[oops"));
    }
}
