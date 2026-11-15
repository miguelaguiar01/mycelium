package mycelium.chunk;

import mycelium.nbt.CompoundTag;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A decoded chunk: the fields the merge understands as typed values, everything else as raw NBT.
 *
 * <p>Lists and maps keep the order of the file they were read from, so encoding an unchanged
 * chunk gives back equal NBT. Callers that need a canonical order sort before encoding.
 *
 * @param dataVersion    the world data version the chunk was saved with
 * @param pos            chunk coordinates ({@code xPos}, {@code zPos})
 * @param minSectionY    the lowest section index ({@code yPos}); −4 in the overworld
 * @param status         generation status; only {@link #STATUS_FULL} chunks are finished terrain
 * @param lastUpdate     game time of the last save
 * @param inhabitedTime  ticks players have spent near the chunk
 * @param sections       sections in ascending Y, including light-only ones
 * @param blockEntities  raw block entity NBT by block position
 * @param blockTicks     pending block ticks, in file order
 * @param fluidTicks     pending fluid ticks, in file order
 * @param heightmaps     derived height data; null when absent. The game rebuilds it on load
 * @param lightOn        the {@code isLightOn} flag; null when absent, as in unfinished chunks.
 *                       Without it the game relights the chunk from its own light sources
 * @param structures     structure starts and references; null when absent
 * @param postProcessing per section, packed positions of blocks that need an update after generation
 * @param extra          every other top-level tag, carried through untouched
 */
public record ChunkData(
        int dataVersion,
        ChunkPos pos,
        int minSectionY,
        String status,
        long lastUpdate,
        long inhabitedTime,
        List<Section> sections,
        Map<BlockPos, CompoundTag> blockEntities,
        List<ScheduledTick> blockTicks,
        List<ScheduledTick> fluidTicks,
        CompoundTag heightmaps,
        Boolean lightOn,
        StructureData structures,
        List<List<Short>> postProcessing,
        CompoundTag extra) {

    public static final String STATUS_FULL = "minecraft:full";

    public ChunkData {
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(extra, "extra");
        sections = List.copyOf(sections);
        blockEntities = Collections.unmodifiableMap(new LinkedHashMap<>(blockEntities));
        blockTicks = List.copyOf(blockTicks);
        fluidTicks = List.copyOf(fluidTicks);
        postProcessing = postProcessing.stream().map(List::copyOf).toList();
    }

    /** Whether generation has finished. Other chunks are work in progress the game may redo. */
    public boolean isFull() {
        return status.equals(STATUS_FULL);
    }

    /** The section with index {@code y}, or null. */
    public Section section(int y) {
        for (Section section : sections) {
            if (section.y() == y) {
                return section;
            }
        }
        return null;
    }

    /** The block state at an absolute position, or null if the chunk has no such section. */
    public BlockState blockState(int x, int y, int z) {
        Section section = section(y >> 4);
        return section == null || section.isLightOnly() ? null : section.blockStates().get(Section.blockIndex(x, y, z));
    }

    /** The biome at an absolute position, or null if the chunk has no such section. */
    public String biome(int x, int y, int z) {
        Section section = section(y >> 4);
        return section == null || section.isLightOnly() ? null : section.biomes().get(Section.biomeIndex(x, y, z));
    }
}
