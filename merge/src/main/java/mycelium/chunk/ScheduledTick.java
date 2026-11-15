package mycelium.chunk;

import mycelium.nbt.CompoundTag;
import mycelium.nbt.IntTag;
import mycelium.nbt.StringTag;
import java.util.Objects;

/**
 * A pending block or fluid tick.
 *
 * @param type     the block or fluid id the tick is for ({@code i})
 * @param priority tick priority ({@code p})
 * @param delay    ticks until it runs, counted from the chunk's last update ({@code t})
 * @param pos      absolute block position ({@code x}, {@code y}, {@code z})
 * @param extra    any other tag of the entry, carried through untouched
 */
public record ScheduledTick(String type, int priority, int delay, BlockPos pos, CompoundTag extra) {

    public ScheduledTick {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(extra, "extra");
    }

    static ScheduledTick decode(CompoundTag tag, String where) throws ChunkFormatException {
        return new ScheduledTick(
                Fields.requiredString(tag, "i", where),
                Fields.requiredInt(tag, "p", where),
                Fields.requiredInt(tag, "t", where),
                new BlockPos(Fields.requiredInt(tag, "x", where), Fields.requiredInt(tag, "y", where),
                        Fields.requiredInt(tag, "z", where)),
                tag.without("i", "p", "t", "x", "y", "z"));
    }

    CompoundTag encode() {
        return CompoundTag.builder()
                .put("i", new StringTag(type))
                .put("p", new IntTag(priority))
                .put("t", new IntTag(delay))
                .put("x", new IntTag(pos.x()))
                .put("y", new IntTag(pos.y()))
                .put("z", new IntTag(pos.z()))
                .putAll(extra)
                .build();
    }
}
