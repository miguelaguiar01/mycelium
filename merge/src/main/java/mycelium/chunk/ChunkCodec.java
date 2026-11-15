package mycelium.chunk;

import mycelium.nbt.ByteTag;
import mycelium.nbt.CompoundTag;
import mycelium.nbt.IntTag;
import mycelium.nbt.ListTag;
import mycelium.nbt.LongTag;
import mycelium.nbt.ShortTag;
import mycelium.nbt.StringTag;
import mycelium.nbt.Tag;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts between a chunk's root NBT and {@link ChunkData}.
 *
 * <p>For a chunk written by Minecraft 26.3, {@code encode(decode(root))} equals {@code root}.
 * Tags the game tolerates as absent decode to their empty value and are written back, which is
 * what the game itself does on its next save. Layout verified in docs/FORMAT-26.3.md.
 */
public final class ChunkCodec {

    private static final String DATA_VERSION = "DataVersion";
    private static final String X_POS = "xPos";
    private static final String Z_POS = "zPos";
    private static final String Y_POS = "yPos";
    private static final String STATUS = "Status";
    private static final String LAST_UPDATE = "LastUpdate";
    private static final String INHABITED_TIME = "InhabitedTime";
    private static final String SECTIONS = "sections";
    private static final String BLOCK_ENTITIES = "block_entities";
    private static final String BLOCK_TICKS = "block_ticks";
    private static final String FLUID_TICKS = "fluid_ticks";
    private static final String HEIGHTMAPS = "Heightmaps";
    private static final String IS_LIGHT_ON = "isLightOn";
    private static final String STRUCTURES = "structures";
    private static final String STARTS = "starts";
    private static final String REFERENCES = "References";
    private static final String POST_PROCESSING = "PostProcessing";

    private static final String[] KNOWN = {
        DATA_VERSION, X_POS, Z_POS, Y_POS, STATUS, LAST_UPDATE, INHABITED_TIME, SECTIONS, BLOCK_ENTITIES,
        BLOCK_TICKS, FLUID_TICKS, HEIGHTMAPS, IS_LIGHT_ON, STRUCTURES, POST_PROCESSING,
    };

    private ChunkCodec() {
    }

    /**
     * Decodes a chunk root.
     *
     * @throws ChunkFormatException if the data version is not 26.3's or the layout is not the verified one
     */
    public static ChunkData decode(CompoundTag root) throws ChunkFormatException {
        int dataVersion = Fields.requiredInt(root, DATA_VERSION, "chunk");
        ChunkPos pos = new ChunkPos(Fields.requiredInt(root, X_POS, "chunk"), Fields.requiredInt(root, Z_POS, "chunk"));
        String where = "chunk " + pos.x() + "," + pos.z();
        DataVersions.require(dataVersion, where);

        List<Section> sections = new ArrayList<>();
        for (Tag element : Fields.required(root, SECTIONS, ListTag.class, where)) {
            Section section = SectionCodec.decode(Fields.compound(element, where + " " + SECTIONS), where);
            if (!sections.isEmpty() && sections.get(sections.size() - 1).y() >= section.y()) {
                throw new ChunkFormatException(where + ": sections are not in ascending Y order");
            }
            sections.add(section);
        }

        Map<BlockPos, CompoundTag> blockEntities = new LinkedHashMap<>();
        for (Tag element : Fields.optionalList(root, BLOCK_ENTITIES, where)) {
            String here = where + " " + BLOCK_ENTITIES;
            CompoundTag blockEntity = Fields.compound(element, here);
            BlockPos at = new BlockPos(Fields.requiredInt(blockEntity, "x", here),
                    Fields.requiredInt(blockEntity, "y", here), Fields.requiredInt(blockEntity, "z", here));
            if (!at.chunk().equals(pos)) {
                throw new ChunkFormatException(here + ": block entity at " + at + " is outside the chunk");
            }
            if (blockEntities.put(at, blockEntity) != null) {
                throw new ChunkFormatException(here + ": two block entities at " + at);
            }
        }

        CompoundTag structuresTag = Fields.optionalCompound(root, STRUCTURES, where);
        StructureData structures = null;
        if (structuresTag != null) {
            String here = where + " " + STRUCTURES;
            CompoundTag starts = Fields.optionalCompound(structuresTag, STARTS, here);
            CompoundTag references = Fields.optionalCompound(structuresTag, REFERENCES, here);
            structures = new StructureData(
                    starts == null ? CompoundTag.empty() : starts,
                    references == null ? CompoundTag.empty() : references,
                    structuresTag.without(STARTS, REFERENCES));
        }

        List<List<Short>> postProcessing = new ArrayList<>();
        for (Tag element : Fields.optionalList(root, POST_PROCESSING, where)) {
            String here = where + " " + POST_PROCESSING;
            if (!(element instanceof ListTag positions)) {
                throw new ChunkFormatException(here + ": element is a " + element.type() + ", expected list");
            }
            List<Short> packed = new ArrayList<>(positions.size());
            for (Tag position : positions) {
                if (!(position instanceof ShortTag value)) {
                    throw new ChunkFormatException(here + ": position is a " + position.type() + ", expected short");
                }
                packed.add(value.value());
            }
            postProcessing.add(packed);
        }

        ByteTag lightOn = Fields.optional(root, IS_LIGHT_ON, ByteTag.class, where);
        return new ChunkData(
                dataVersion,
                pos,
                Fields.requiredInt(root, Y_POS, where),
                Fields.requiredString(root, STATUS, where),
                Fields.optionalLong(root, LAST_UPDATE, 0, where),
                Fields.optionalLong(root, INHABITED_TIME, 0, where),
                sections,
                blockEntities,
                ticks(root, BLOCK_TICKS, where),
                ticks(root, FLUID_TICKS, where),
                Fields.optionalCompound(root, HEIGHTMAPS, where),
                lightOn == null ? null : lightOn.value() != 0,
                structures,
                postProcessing,
                root.without(KNOWN));
    }

    /**
     * A stored chunk without heightmaps, the {@code isLightOn} flag, light arrays and light-only
     * sections, worked out on its tags alone. For a chunk the game wrote this is what decoding
     * it, dropping that data and encoding it again gives, at a fraction of the cost, because the
     * blocks are never unpacked. It also means that the inside of the sections is not checked.
     *
     * @return the chunk without that data; the root itself if the chunk is not finished, which
     *         keeps its light; or null if this is not a 26.3 chunk at {@code pos} with a list of
     *         sections, so that the caller has to decode it to say what is wrong
     */
    public static CompoundTag withoutDerivedData(CompoundTag root, ChunkPos pos) {
        if (!(root.get(DATA_VERSION) instanceof IntTag version) || version.value() != DataVersions.MC_26_3
                || !(root.get(X_POS) instanceof IntTag x) || x.value() != pos.x()
                || !(root.get(Z_POS) instanceof IntTag z) || z.value() != pos.z()
                || !(root.get(STATUS) instanceof StringTag status) || !(root.get(SECTIONS) instanceof ListTag stored)) {
            return null;
        }
        if (!status.value().equals(ChunkData.STATUS_FULL)) {
            return root;
        }
        List<Tag> sections = new ArrayList<>();
        for (Tag element : stored) {
            if (!(element instanceof CompoundTag section)) {
                return null;
            }
            CompoundTag kept = SectionCodec.withoutLight(section);
            if (kept != null) {
                sections.add(kept);
            }
        }
        return root.without(HEIGHTMAPS, IS_LIGHT_ON).with(SECTIONS, ListTag.of(sections));
    }

    private static List<ScheduledTick> ticks(CompoundTag root, String key, String where) throws ChunkFormatException {
        List<ScheduledTick> ticks = new ArrayList<>();
        for (Tag element : Fields.optionalList(root, key, where)) {
            ticks.add(ScheduledTick.decode(Fields.compound(element, where + " " + key), where + " " + key));
        }
        return ticks;
    }

    /** Encodes a chunk as the game writes it. Empty lists get element type END, as in game files. */
    public static CompoundTag encode(ChunkData chunk) {
        CompoundTag.Builder root = CompoundTag.builder();
        root.put(DATA_VERSION, new IntTag(chunk.dataVersion()));
        root.put(X_POS, new IntTag(chunk.pos().x()));
        root.put(Z_POS, new IntTag(chunk.pos().z()));
        root.put(Y_POS, new IntTag(chunk.minSectionY()));
        root.put(STATUS, new StringTag(chunk.status()));
        root.put(LAST_UPDATE, new LongTag(chunk.lastUpdate()));
        root.put(INHABITED_TIME, new LongTag(chunk.inhabitedTime()));
        if (chunk.lightOn() != null) {
            root.put(IS_LIGHT_ON, new ByteTag((byte) (chunk.lightOn() ? 1 : 0)));
        }

        List<Tag> sections = new ArrayList<>();
        for (Section section : chunk.sections()) {
            sections.add(SectionCodec.encode(section));
        }
        root.put(SECTIONS, ListTag.of(sections));
        root.put(BLOCK_ENTITIES, ListTag.of(List.copyOf(chunk.blockEntities().values())));
        root.put(BLOCK_TICKS, ListTag.of(chunk.blockTicks().stream().map(ScheduledTick::encode).toList()));
        root.put(FLUID_TICKS, ListTag.of(chunk.fluidTicks().stream().map(ScheduledTick::encode).toList()));
        root.putIfPresent(HEIGHTMAPS, chunk.heightmaps());

        if (chunk.structures() != null) {
            root.put(STRUCTURES, CompoundTag.builder()
                    .put(STARTS, chunk.structures().starts())
                    .put(REFERENCES, chunk.structures().references())
                    .putAll(chunk.structures().extra())
                    .build());
        }

        List<Tag> postProcessing = new ArrayList<>();
        for (List<Short> positions : chunk.postProcessing()) {
            postProcessing.add(ListTag.of(positions.stream().map(ShortTag::new).toList()));
        }
        root.put(POST_PROCESSING, ListTag.of(postProcessing));
        root.putAll(chunk.extra());
        return root.build();
    }
}
