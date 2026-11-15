package mycelium.chunk;

import mycelium.nbt.CompoundTag;
import java.util.Objects;

/**
 * The {@code structures} tag of a chunk.
 *
 * @param starts     raw NBT per structure id: the structures that start in this chunk
 * @param references raw NBT per structure id: packed positions of chunks whose structures reach here
 * @param extra      any other tag inside {@code structures}, carried through untouched
 */
public record StructureData(CompoundTag starts, CompoundTag references, CompoundTag extra) {

    public StructureData {
        Objects.requireNonNull(starts, "starts");
        Objects.requireNonNull(references, "references");
        Objects.requireNonNull(extra, "extra");
    }
}
