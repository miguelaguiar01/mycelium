package mycelium.chunk;

import mycelium.nbt.ByteTag;
import mycelium.nbt.CompoundTag;
import mycelium.nbt.ListTag;
import mycelium.nbt.LongArrayTag;
import mycelium.nbt.StringTag;
import mycelium.nbt.Tag;
import java.util.ArrayList;
import java.util.List;

/** Converts between a section's NBT and {@link Section}. Layout verified in docs/FORMAT-26.3.md. */
public final class SectionCodec {

    private static final String Y = "Y";
    private static final String BLOCK_STATES = "block_states";
    private static final String BIOMES = "biomes";
    private static final String BLOCK_LIGHT = "BlockLight";
    private static final String SKY_LIGHT = "SkyLight";
    private static final String PALETTE = "palette";
    private static final String DATA = "data";

    private SectionCodec() {
    }

    /**
     * A stored section without its light, or null if light is all it holds. The same as decoding
     * it, dropping the light and encoding it again, without unpacking its blocks.
     */
    static CompoundTag withoutLight(CompoundTag tag) {
        return tag.contains(BLOCK_STATES) ? tag.without(BLOCK_LIGHT, SKY_LIGHT) : null;
    }

    public static Section decode(CompoundTag tag, String where) throws ChunkFormatException {
        int y = Fields.requiredByte(tag, Y, where);
        String here = where + " section " + y;
        CompoundTag blockStates = Fields.optionalCompound(tag, BLOCK_STATES, here);
        CompoundTag biomes = Fields.optionalCompound(tag, BIOMES, here);
        if ((blockStates == null) != (biomes == null)) {
            throw new ChunkFormatException(here + ": has only one of " + BLOCK_STATES + " and " + BIOMES);
        }
        PalettedContainer<BlockState> blocks = null;
        PalettedContainer<String> biomeCells = null;
        if (blockStates != null) {
            String blocksHere = here + " " + BLOCK_STATES;
            List<BlockState> palette = new ArrayList<>();
            for (Tag entry : paletteOf(blockStates, blocksHere)) {
                palette.add(BlockState.fromTag(entry, blocksHere));
            }
            blocks = PalettedContainer.unpack(palette, dataOf(blockStates, blocksHere), Section.BLOCKS,
                    PalettedContainer.MIN_BLOCK_BITS, blocksHere);

            String biomesHere = here + " " + BIOMES;
            List<String> biomePalette = new ArrayList<>();
            for (Tag entry : paletteOf(biomes, biomesHere)) {
                if (!(ListTag.unwrap(entry) instanceof StringTag biome)) {
                    throw new ChunkFormatException(biomesHere + ": palette entry is a " + entry.type());
                }
                biomePalette.add(biome.value());
            }
            biomeCells = PalettedContainer.unpack(biomePalette, dataOf(biomes, biomesHere), Section.BIOME_CELLS,
                    PalettedContainer.MIN_BIOME_BITS, biomesHere);
        }
        return new Section(y, blocks, biomeCells,
                Fields.optionalBytes(tag, BLOCK_LIGHT, here), Fields.optionalBytes(tag, SKY_LIGHT, here),
                tag.without(Y, BLOCK_STATES, BIOMES, BLOCK_LIGHT, SKY_LIGHT));
    }

    public static CompoundTag encode(Section section) {
        CompoundTag.Builder tag = CompoundTag.builder();
        if (section.blockStates() != null) {
            List<Tag> palette = new ArrayList<>();
            for (BlockState state : section.blockStates().palette()) {
                palette.add(state.toTag());
            }
            tag.put(BLOCK_STATES, container(ListTag.mixed(palette),
                    section.blockStates().pack(PalettedContainer.MIN_BLOCK_BITS)));
        }
        if (section.biomes() != null) {
            List<Tag> palette = new ArrayList<>();
            for (String biome : section.biomes().palette()) {
                palette.add(new StringTag(biome));
            }
            tag.put(BIOMES, container(ListTag.of(palette), section.biomes().pack(PalettedContainer.MIN_BIOME_BITS)));
        }
        tag.putIfPresent(BLOCK_LIGHT, section.blockLight());
        tag.putIfPresent(SKY_LIGHT, section.skyLight());
        tag.putAll(section.extra());
        tag.put(Y, new ByteTag((byte) section.y()));
        return tag.build();
    }

    private static ListTag paletteOf(CompoundTag container, String where) throws ChunkFormatException {
        requireOnly(container, where);
        return Fields.required(container, PALETTE, ListTag.class, where);
    }

    private static long[] dataOf(CompoundTag container, String where) throws ChunkFormatException {
        LongArrayTag data = Fields.optionalLongs(container, DATA, where);
        return data == null ? null : data.longs();
    }

    /** A paletted container holds only a palette and data; anything else would be lost on re-encoding. */
    private static void requireOnly(CompoundTag container, String where) throws ChunkFormatException {
        for (String key : container.keys()) {
            if (!key.equals(PALETTE) && !key.equals(DATA)) {
                throw new ChunkFormatException(where + ": unknown field " + key);
            }
        }
    }

    private static CompoundTag container(ListTag palette, long[] data) {
        CompoundTag.Builder tag = CompoundTag.builder();
        if (data != null) {
            tag.put(DATA, new LongArrayTag(data));
        }
        return tag.put(PALETTE, palette).build();
    }
}
