package mycelium.chunk;

import mycelium.nbt.ByteArrayTag;
import mycelium.nbt.CompoundTag;
import java.util.Objects;

/**
 * One 16×16×16 section of a chunk.
 *
 * @param y           the section's Y index; block y = {@code y * 16 + localY}
 * @param blockStates 4,096 block states, cell index {@code localY * 256 + localZ * 16 + localX};
 *                    null for a light-only section, which the game keeps above the top of a chunk
 * @param biomes      64 biome ids for cells of 4×4×4 blocks, cell index
 *                    {@code cellY * 16 + cellZ * 4 + cellX}; null for a light-only section
 * @param blockLight  2,048 bytes of block light, or null when the file has none
 * @param skyLight    2,048 bytes of sky light, or null when the file has none
 * @param extra       every other tag of the section, carried through untouched
 */
public record Section(int y, PalettedContainer<BlockState> blockStates, PalettedContainer<String> biomes,
                      ByteArrayTag blockLight, ByteArrayTag skyLight, CompoundTag extra) {

    public static final int BLOCKS = 4096;
    public static final int BIOME_CELLS = 64;

    public Section {
        Objects.requireNonNull(extra, "extra");
        if (blockStates != null && blockStates.size() != BLOCKS) {
            throw new IllegalArgumentException("a section holds " + BLOCKS + " block states");
        }
        if (biomes != null && biomes.size() != BIOME_CELLS) {
            throw new IllegalArgumentException("a section holds " + BIOME_CELLS + " biome cells");
        }
    }

    /** The cell index of a block inside its section, from absolute or local coordinates. */
    public static int blockIndex(int x, int y, int z) {
        return (y & 15) << 8 | (z & 15) << 4 | (x & 15);
    }

    /** The biome cell index of a block inside its section, from absolute or local coordinates. */
    public static int biomeIndex(int x, int y, int z) {
        return (y >> 2 & 3) << 4 | (z >> 2 & 3) << 2 | (x >> 2 & 3);
    }

    /** Whether this section stores only light, with no blocks or biomes. */
    public boolean isLightOnly() {
        return blockStates == null;
    }

    /** A copy without light data. */
    public Section withoutLight() {
        return new Section(y, blockStates, biomes, null, null, extra);
    }
}
