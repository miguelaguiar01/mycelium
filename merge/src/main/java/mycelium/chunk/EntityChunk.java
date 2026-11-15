package mycelium.chunk;

import mycelium.nbt.CompoundTag;
import java.util.List;
import java.util.Objects;

/**
 * One chunk of an entity region file ({@code entities/r.X.Z.mca}).
 *
 * @param dataVersion the world data version the chunk was saved with
 * @param pos         chunk coordinates ({@code Position})
 * @param entities    top-level entities as raw NBT; riders are nested inside their vehicle's
 *                    {@code Passengers} list. See {@link Entities#flatten}
 * @param extra       every other top-level tag, carried through untouched
 */
public record EntityChunk(int dataVersion, ChunkPos pos, List<CompoundTag> entities, CompoundTag extra) {

    public EntityChunk {
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(extra, "extra");
        entities = List.copyOf(entities);
    }
}
