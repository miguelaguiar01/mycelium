package mycelium.testing;

import mycelium.chunk.BlockPos;
import mycelium.chunk.BlockState;
import mycelium.chunk.ChunkData;
import mycelium.chunk.ChunkPos;
import mycelium.chunk.DataVersions;
import mycelium.chunk.PalettedContainer;
import mycelium.chunk.ScheduledTick;
import mycelium.chunk.Section;
import mycelium.chunk.StructureData;
import mycelium.merge.MergeContext;
import mycelium.merge.Precedence;
import mycelium.merge.Side;
import mycelium.nbt.ByteArrayTag;
import mycelium.nbt.CompoundTag;
import mycelium.nbt.IntTag;
import mycelium.nbt.LongArrayTag;
import mycelium.nbt.StringTag;
import mycelium.rules.BlockTable;
import mycelium.rules.Rules;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small synthetic chunks for merge tests, and functions that edit a chunk the way a player would. */
public final class TestChunks {

    public static final String OVERWORLD = "minecraft:overworld";
    public static final BlockTable TABLE = BlockTable.vanilla();
    public static final Rules RULES = Rules.vanilla(TABLE);

    private TestChunks() {
    }

    public static MergeContext context(Side newer) {
        return new MergeContext(Precedence.always(newer), RULES, OVERWORLD);
    }

    /** A finished overworld chunk of air, with heightmaps and light present so stripping shows. */
    public static ChunkData empty(int chunkX, int chunkZ) {
        List<Section> sections = new ArrayList<>();
        for (int y = -4; y < 20; y++) {
            sections.add(new Section(y, PalettedContainer.filled(Section.BLOCKS, BlockState.of("minecraft:air")),
                    PalettedContainer.filled(Section.BIOME_CELLS, "minecraft:plains"), null,
                    y == 4 ? new ByteArrayTag(new byte[2048]) : null, CompoundTag.empty()));
        }
        sections.add(new Section(20, null, null, null, new ByteArrayTag(new byte[2048]), CompoundTag.empty()));
        return new ChunkData(DataVersions.MC_26_3, new ChunkPos(chunkX, chunkZ), -4, ChunkData.STATUS_FULL, 100, 10,
                sections, Map.of(), List.of(), List.of(),
                CompoundTag.of("WORLD_SURFACE", new LongArrayTag(new long[37])), Boolean.TRUE,
                new StructureData(CompoundTag.empty(), CompoundTag.empty(), CompoundTag.empty()),
                Collections.nCopies(24, List.<Short>of()), CompoundTag.empty());
    }

    /** The chunk with one block set. A block entity or tick at that position is left alone. */
    public static ChunkData set(ChunkData chunk, int x, int y, int z, String state) {
        List<Section> sections = new ArrayList<>(chunk.sections());
        for (int i = 0; i < sections.size(); i++) {
            Section section = sections.get(i);
            if (section.y() == y >> 4 && !section.isLightOnly()) {
                List<BlockState> cells = section.blockStates().toList();
                cells.set(Section.blockIndex(x, y, z), BlockState.parse(state));
                sections.set(i, new Section(section.y(), PalettedContainer.of(cells), section.biomes(),
                        section.blockLight(), section.skyLight(), section.extra()));
                return withSections(chunk, sections);
            }
        }
        throw new IllegalArgumentException("no section for y=" + y);
    }

    public static ChunkData setBiome(ChunkData chunk, int x, int y, int z, String biome) {
        List<Section> sections = new ArrayList<>(chunk.sections());
        for (int i = 0; i < sections.size(); i++) {
            Section section = sections.get(i);
            if (section.y() == y >> 4 && !section.isLightOnly()) {
                List<String> cells = section.biomes().toList();
                cells.set(Section.biomeIndex(x, y, z), biome);
                sections.set(i, new Section(section.y(), section.blockStates(), PalettedContainer.of(cells),
                        section.blockLight(), section.skyLight(), section.extra()));
            }
        }
        return withSections(chunk, sections);
    }

    public static ChunkData withSections(ChunkData chunk, List<Section> sections) {
        return new ChunkData(chunk.dataVersion(), chunk.pos(), chunk.minSectionY(), chunk.status(), chunk.lastUpdate(),
                chunk.inhabitedTime(), sections, chunk.blockEntities(), chunk.blockTicks(), chunk.fluidTicks(),
                chunk.heightmaps(), chunk.lightOn(), chunk.structures(), chunk.postProcessing(), chunk.extra());
    }

    /** A minimal block entity compound with one distinguishing field. */
    public static CompoundTag blockEntity(String id, int x, int y, int z, String note) {
        return CompoundTag.builder().put("id", new StringTag(id)).put("x", new IntTag(x)).put("y", new IntTag(y))
                .put("z", new IntTag(z)).put("note", new StringTag(note)).build();
    }

    /** The chunk with a block entity put at, or with null removed from, its position. */
    public static ChunkData setBlockEntity(ChunkData chunk, BlockPos pos, CompoundTag blockEntity) {
        Map<BlockPos, CompoundTag> blockEntities = new LinkedHashMap<>(chunk.blockEntities());
        if (blockEntity == null) {
            blockEntities.remove(pos);
        } else {
            blockEntities.put(pos, blockEntity);
        }
        return new ChunkData(chunk.dataVersion(), chunk.pos(), chunk.minSectionY(), chunk.status(), chunk.lastUpdate(),
                chunk.inhabitedTime(), chunk.sections(), blockEntities, chunk.blockTicks(), chunk.fluidTicks(),
                chunk.heightmaps(), chunk.lightOn(), chunk.structures(), chunk.postProcessing(), chunk.extra());
    }

    public static ChunkData withTicks(ChunkData chunk, List<ScheduledTick> blockTicks) {
        return new ChunkData(chunk.dataVersion(), chunk.pos(), chunk.minSectionY(), chunk.status(), chunk.lastUpdate(),
                chunk.inhabitedTime(), chunk.sections(), chunk.blockEntities(), blockTicks, chunk.fluidTicks(),
                chunk.heightmaps(), chunk.lightOn(), chunk.structures(), chunk.postProcessing(), chunk.extra());
    }

    public static ChunkData withExtra(ChunkData chunk, CompoundTag extra) {
        return new ChunkData(chunk.dataVersion(), chunk.pos(), chunk.minSectionY(), chunk.status(), chunk.lastUpdate(),
                chunk.inhabitedTime(), chunk.sections(), chunk.blockEntities(), chunk.blockTicks(), chunk.fluidTicks(),
                chunk.heightmaps(), chunk.lightOn(), chunk.structures(), chunk.postProcessing(), extra);
    }

    public static ChunkData withStatus(ChunkData chunk, String status, long lastUpdate) {
        return new ChunkData(chunk.dataVersion(), chunk.pos(), chunk.minSectionY(), status, lastUpdate,
                chunk.inhabitedTime(), chunk.sections(), chunk.blockEntities(), chunk.blockTicks(), chunk.fluidTicks(),
                chunk.heightmaps(), status.equals(ChunkData.STATUS_FULL) ? chunk.lightOn() : null, chunk.structures(),
                chunk.postProcessing(), chunk.extra());
    }

    public static ScheduledTick tick(String type, int x, int y, int z) {
        return new ScheduledTick(type, 0, 5, new BlockPos(x, y, z), CompoundTag.empty());
    }
}
