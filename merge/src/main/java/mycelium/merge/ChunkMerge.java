package mycelium.merge;

import mycelium.chunk.BlockPos;
import mycelium.chunk.BlockState;
import mycelium.chunk.ChunkData;
import mycelium.chunk.ChunkPos;
import mycelium.chunk.ScheduledTick;
import mycelium.chunk.Section;
import mycelium.chunk.StructureData;
import mycelium.nbt.CompoundTag;
import mycelium.report.Category;
import mycelium.report.ReportEntry;
import mycelium.report.Severity;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;

/**
 * The merge of one finished chunk, in two steps.
 *
 * <p>{@link #begin} merges the sections cell by cell. Unit repair may then replace individual
 * blocks through {@link #take}. {@link #finish} derives everything that follows the blocks
 * (block entities, scheduled ticks, heightmaps and light) and merges the chunk-level fields.
 *
 * <p>All three inputs must be finished chunks at the same position. Missing and unfinished
 * chunks are handled before this class is reached.
 */
public final class ChunkMerge {

    private final ChunkData base;   // null when there is no ancestor
    private final ChunkData ours;
    private final ChunkData theirs;
    private final MergeContext context;
    private final Side newer;
    private final SortedMap<Integer, SectionMerge> sections = new TreeMap<>();
    private final List<ReportEntry> entries = new ArrayList<>();
    private final SortedSet<String> unknownBlocks = new TreeSet<>();
    private final SortedSet<BlockPos> refresh = new TreeSet<>();

    private ChunkMerge(ChunkData base, ChunkData ours, ChunkData theirs, MergeContext context) {
        this.base = base;
        this.ours = ours;
        this.theirs = theirs;
        this.context = context;
        this.newer = context.precedence().newerIn(ours.pos());
    }

    /**
     * Merges the sections of three versions of a chunk.
     *
     * @param base the common ancestor, or null if there is none
     * @throws MergeRefusedException if the inputs are unfinished or disagree on version, position or height
     */
    public static ChunkMerge begin(ChunkData base, ChunkData ours, ChunkData theirs, MergeContext context)
            throws MergeRefusedException {
        for (ChunkData chunk : new ChunkData[] {base, ours, theirs}) {
            if (chunk == null) {
                continue;
            }
            if (!chunk.isFull()) {
                throw new MergeRefusedException("chunk " + chunk.pos() + " is not fully generated");
            }
            if (chunk.dataVersion() != ours.dataVersion() || !chunk.pos().equals(ours.pos())
                    || chunk.minSectionY() != ours.minSectionY()) {
                throw new MergeRefusedException("the inputs for chunk " + ours.pos()
                        + " disagree on data version, position or lowest section");
            }
        }
        ChunkMerge merge = new ChunkMerge(base, ours, theirs, context);
        SortedSet<Integer> ys = new TreeSet<>();
        for (ChunkData chunk : new ChunkData[] {base, ours, theirs}) {
            if (chunk != null) {
                for (Section section : chunk.sections()) {
                    if (!section.isLightOnly()) {
                        ys.add(section.y());
                    }
                }
            }
        }
        // Whether the newer side's chunk is not the base's any more. A chunk a player was near is saved again,
        // if only with a later time. Worked out the first time a section asks.
        boolean[] played = new boolean[2];
        BooleanSupplier newerPlayed = () -> {
            if (!played[0]) {
                played[0] = true;
                played[1] = base != null && !(merge.newer == Side.OURS ? ours : theirs).equals(base);
            }
            return played[1];
        };
        for (int y : ys) {
            merge.sections.put(y, SectionMerge.merge(base == null ? null : sectionOrAir(base, y),
                    sectionOrAir(ours, y), sectionOrAir(theirs, y), ours.pos(), context, newerPlayed, merge.entries,
                    merge.unknownBlocks));
        }
        return merge;
    }

    private static Section sectionOrAir(ChunkData chunk, int y) {
        Section section = chunk.section(y);
        return section == null || section.isLightOnly() ? SectionMerge.missing(y) : section;
    }

    public ChunkPos pos() {
        return ours.pos();
    }

    /** The merged block state at an absolute position, or null outside the chunk's sections. */
    public BlockState merged(BlockPos pos) {
        SectionMerge section = sections.get(pos.y() >> 4);
        return section == null ? null : section.blocks().get(Section.blockIndex(pos.x(), pos.y(), pos.z()));
    }

    /** Where the merged block at a position came from; BASE outside the chunk's sections. */
    public Origin origin(BlockPos pos) {
        SectionMerge section = sections.get(pos.y() >> 4);
        return section == null ? Origin.BASE : section.origin(Section.blockIndex(pos.x(), pos.y(), pos.z()));
    }

    /** One input's block state at a position; {@code side} null asks for the base. May be null. */
    public BlockState input(Side side, BlockPos pos) {
        SectionMerge section = sections.get(pos.y() >> 4);
        return section == null ? null : section.input(side, Section.blockIndex(pos.x(), pos.y(), pos.z()));
    }

    /** Replaces the merged block at a position with {@code side}'s block. Used by unit repair. */
    public void take(BlockPos pos, Side side) {
        sections.get(pos.y() >> 4).take(Section.blockIndex(pos.x(), pos.y(), pos.z()), side);
    }

    /** Replaces the merged block at a position with a state that neither side has. Used by unit repair. */
    public void put(BlockPos pos, BlockState state) {
        sections.get(pos.y() >> 4).put(Section.blockIndex(pos.x(), pos.y(), pos.z()), state);
    }

    /** Every position whose block did not come unchanged from the base, in position order. */
    public SortedSet<BlockPos> changedCells() {
        SortedSet<BlockPos> changed = new TreeSet<>();
        for (SectionMerge section : sections.values()) {
            for (int index : section.changedCells()) {
                changed.add(new BlockPos(pos().x() * 16 + (index & 15), section.y() * 16 + (index >> 8),
                        pos().z() * 16 + (index >> 4 & 15)));
            }
        }
        return changed;
    }

    /**
     * Asks the game to refresh the block at a position when it loads the chunk, by listing it in
     * the chunk's post-processing list. The game then recomputes states that depend on the
     * block's neighbours, such as fence connections, and lets fluids flow.
     */
    public void refreshOnLoad(BlockPos pos) {
        if (sections.containsKey(pos.y() >> 4)) {
            refresh.add(pos);
        }
    }

    /** Adds an entry to this chunk's part of the report. */
    public void report(ReportEntry entry) {
        entries.add(entry);
    }

    /** The side whose value wins "newer side" rules in this chunk. */
    public Side newer() {
        return newer;
    }

    /** Completes the merge with the blocks as they now are. */
    public ChunkMergeResult finish() {
        // Heightmaps and light describe the blocks. They stay valid only if the merged blocks are
        // exactly one side's blocks. Light also depends on the chunks around, so where the blocks
        // are both sides', the data of the side that changed it is kept, or the newer side's.
        ChunkData first = newer == Side.OURS ? ours : theirs;
        ChunkData second = newer == Side.OURS ? theirs : ours;
        boolean firstFits = sections.values().stream().allMatch(section -> section.blocksEqual(newer));
        boolean secondFits = sections.values().stream().allMatch(section -> section.blocksEqual(newer.other()));
        ChunkData derivedFrom = firstFits && secondFits
                ? base != null && sameDerivedData(first, base) && !sameDerivedData(second, base) ? second : first
                : firstFits ? first : secondFits ? second : null;
        List<Section> merged = new ArrayList<>();
        for (SectionMerge section : sections.values()) {
            Section source = derivedFrom == null ? null : derivedFrom.section(section.y());
            merged.add(new Section(section.y(), section.blocks(), section.biomes(),
                    source == null ? null : source.blockLight(), source == null ? null : source.skyLight(),
                    section.extra()));
        }
        if (derivedFrom != null) {
            for (Section section : derivedFrom.sections()) {
                if (section.isLightOnly()) {
                    merged.add(section);
                }
            }
            merged.sort((a, b) -> Integer.compare(a.y(), b.y()));
        }

        CompoundTag baseExtra = base == null ? null : base.extra();
        CompoundTag extra = Values.mergeCompound(baseExtra, ours.extra(), theirs.extra(), newer,
                key -> entries.add(new ReportEntry(Severity.CONFLICT, Category.UNKNOWN_FIELD, context.dimension(),
                        corner(), text(baseExtra, key), text(ours.extra(), key), text(theirs.extra(), key), newer,
                        "chunk field " + key)));

        ChunkData chunk = new ChunkData(
                ours.dataVersion(), ours.pos(), ours.minSectionY(), ChunkData.STATUS_FULL,
                Math.max(ours.lastUpdate(), theirs.lastUpdate()),
                Math.max(ours.inhabitedTime(), theirs.inhabitedTime()),
                merged,
                blockEntities(),
                ticks(base == null ? List.of() : base.blockTicks(), ours.blockTicks(), theirs.blockTicks()),
                ticks(base == null ? List.of() : base.fluidTicks(), ours.fluidTicks(), theirs.fluidTicks()),
                derivedFrom == null ? null : derivedFrom.heightmaps(),
                derivedFrom == null ? null : derivedFrom.lightOn(),
                structures(),
                postProcessing(),
                extra);
        Side blocksOf = derivedFrom == null ? null : derivedFrom == ours ? Side.OURS : Side.THEIRS;
        return new ChunkMergeResult(chunk, entries, unknownBlocks, blocksOf);
    }

    private static boolean sameDerivedData(ChunkData a, ChunkData b) {
        return Objects.equals(a.heightmaps(), b.heightmaps()) && Objects.equals(a.lightOn(), b.lightOn())
                && light(a).equals(light(b));
    }

    private static Map<Integer, List<Object>> light(ChunkData chunk) {
        Map<Integer, List<Object>> light = new TreeMap<>();
        for (Section section : chunk.sections()) {
            if (section.blockLight() != null || section.skyLight() != null) {
                light.put(section.y(), Arrays.asList(section.blockLight(), section.skyLight()));
            }
        }
        return light;
    }

    private BlockPos corner() {
        return new BlockPos(pos().x() * 16, ours.minSectionY() * 16, pos().z() * 16);
    }

    private static String text(CompoundTag compound, String key) {
        return compound == null || compound.get(key) == null ? null : compound.get(key).toString();
    }

    /**
     * A block entity follows its block, and a block is the same block as long as its id is. A chest
     * that became half of a double chest on one side, or a furnace that is lit on one, is still
     * that chest and that furnace. Where both sides have a block of the same id, its block entity
     * is merged: containers slot by slot, everything else as a whole value. Where the ids differ,
     * the block entity goes with the block that was taken, and if the other side had changed its
     * own, that change is lost and recorded.
     */
    private Map<BlockPos, CompoundTag> blockEntities() {
        SortedSet<BlockPos> positions = new TreeSet<>(ours.blockEntities().keySet());
        positions.addAll(theirs.blockEntities().keySet());
        Map<BlockPos, CompoundTag> merged = new LinkedHashMap<>();
        for (BlockPos pos : positions) {
            CompoundTag mine = ours.blockEntities().get(pos);
            CompoundTag other = theirs.blockEntities().get(pos);
            CompoundTag was = base == null ? null : base.blockEntities().get(pos);
            BlockState oursBlock = input(Side.OURS, pos);
            BlockState theirsBlock = input(Side.THEIRS, pos);
            BlockState baseBlock = input(null, pos);
            CompoundTag value;
            if (oursBlock != null && theirsBlock != null && oursBlock.name().equals(theirsBlock.name())) {
                // The base's block entity is the ancestor only if the base had this kind of block here too.
                boolean sameInBase = baseBlock != null && baseBlock.name().equals(oursBlock.name());
                value = BlockEntityMerge.merge(sameInBase ? was : null, mine, other, pos, newer, context, entries);
            } else {
                Side side = origin(pos) == Origin.OURS ? Side.OURS : origin(pos) == Origin.THEIRS ? Side.THEIRS : newer;
                value = side == Side.OURS ? mine : other;
                CompoundTag lost = side == Side.OURS ? other : mine;
                BlockState lostBlock = side == Side.OURS ? theirsBlock : oursBlock;
                boolean changed = lost != null && was != null && baseBlock != null && lostBlock != null
                        && baseBlock.name().equals(lostBlock.name())
                        && !context.rules().withoutProgress(lost).equals(context.rules().withoutProgress(was));
                if (changed) {
                    entries.add(new ReportEntry(Severity.CONFLICT, Category.BLOCK_ENTITY, context.dimension(), pos,
                            was.toString(), mine == null ? null : mine.toString(), other == null ? null : other.toString(),
                            side, "the block was replaced by " + side.label() + ", so what " + side.other().label()
                            + " changed in it is lost"));
                }
            }
            if (value != null) {
                merged.put(pos, value);
            }
        }
        return merged;
    }

    /**
     * Keeps the ticks of each position from the side its block came from. Where the block is the
     * same on both sides, the ticks at that position follow the 3-way rule, and if both sides
     * changed them, both sides' ticks are kept without duplicates. Ticks keep their file order,
     * the newer side's first.
     */
    private List<ScheduledTick> ticks(List<ScheduledTick> baseTicks, List<ScheduledTick> oursTicks,
                                      List<ScheduledTick> theirsTicks) {
        Map<BlockPos, List<ScheduledTick>> was = byPosition(baseTicks);
        Map<BlockPos, List<ScheduledTick>> mine = byPosition(oursTicks);
        Map<BlockPos, List<ScheduledTick>> other = byPosition(theirsTicks);
        Set<BlockPos> positions = new HashSet<>(mine.keySet());
        positions.addAll(other.keySet());
        Set<ScheduledTick> kept = new HashSet<>();
        for (BlockPos pos : positions) {
            List<ScheduledTick> a = mine.getOrDefault(pos, List.of());
            List<ScheduledTick> b = other.getOrDefault(pos, List.of());
            List<ScheduledTick> o = was.getOrDefault(pos, List.of());
            Origin origin = origin(pos);
            if (origin == Origin.OURS) {
                kept.addAll(a);
            } else if (origin == Origin.THEIRS) {
                kept.addAll(b);
            } else if (origin == Origin.BASE && b.equals(o)) {
                kept.addAll(a);
            } else if (origin == Origin.BASE && a.equals(o)) {
                kept.addAll(b);
            } else {
                // Both sides changed the ticks here. A block is ticked once per type: the newer side's
                // tick stands, and the older side adds only types the newer side has none of.
                List<ScheduledTick> first = newer == Side.OURS ? a : b;
                kept.addAll(first);
                for (ScheduledTick tick : newer == Side.OURS ? b : a) {
                    if (first.stream().noneMatch(taken -> taken.type().equals(tick.type()))) {
                        kept.add(tick);
                    }
                }
            }
        }
        // If what is kept is exactly one side's ticks, keep that side's list and its order as they are.
        for (Side side : new Side[] {newer, newer.other()}) {
            List<ScheduledTick> list = side == Side.OURS ? oursTicks : theirsTicks;
            if (kept.size() == list.size() && kept.containsAll(list)) {
                return list;
            }
        }
        List<ScheduledTick> merged = new ArrayList<>();
        for (Side side : new Side[] {newer, newer.other()}) {
            for (ScheduledTick tick : side == Side.OURS ? oursTicks : theirsTicks) {
                if (kept.remove(tick)) {
                    merged.add(tick);
                }
            }
        }
        return merged;
    }

    private static Map<BlockPos, List<ScheduledTick>> byPosition(List<ScheduledTick> ticks) {
        Map<BlockPos, List<ScheduledTick>> byPosition = new LinkedHashMap<>();
        for (ScheduledTick tick : ticks) {
            byPosition.computeIfAbsent(tick.pos(), pos -> new ArrayList<>()).add(tick);
        }
        return byPosition;
    }

    private StructureData structures() {
        if (ours.structures() == null && theirs.structures() == null) {
            return null;
        }
        StructureData o = base == null ? null : base.structures();
        StructureData a = ours.structures();
        StructureData b = theirs.structures();
        return new StructureData(
                Values.mergeCompound(o == null ? null : o.starts(), a == null ? null : a.starts(),
                        b == null ? null : b.starts(), newer, key -> { }),
                Values.mergeCompound(o == null ? null : o.references(), a == null ? null : a.references(),
                        b == null ? null : b.references(), newer, key -> { }),
                Values.mergeCompound(o == null ? null : o.extra(), a == null ? null : a.extra(),
                        b == null ? null : b.extra(), newer, key -> { }));
    }

    /**
     * The list of the side that changed it, or the union of both lists if both did, plus the
     * positions marked for a refresh, per section. The game empties the list when it loads the
     * chunk, so a side that never loaded the chunk must not bring the base's entries back.
     */
    private List<List<Short>> postProcessing() {
        List<List<Short>> o = base == null ? null : base.postProcessing();
        List<List<Short>> a = ours.postProcessing();
        List<List<Short>> b = theirs.postProcessing();
        if (b.equals(o)) {
            b = a;
        } else if (a.equals(o)) {
            a = b;
        }
        if (a.equals(b) && refresh.isEmpty()) {
            return a;
        }
        int count = Math.max(Math.max(a.size(), b.size()), sections.isEmpty() ? 0 : sections.lastKey() - ours.minSectionY() + 1);
        List<List<Short>> merged = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            SortedSet<Short> union = new TreeSet<>();
            for (BlockPos pos : refresh) {
                if ((pos.y() >> 4) - ours.minSectionY() == i) {
                    // Packed as the game packs it: x, then y, then z of the block inside its section.
                    union.add((short) (pos.x() & 15 | (pos.y() & 15) << 4 | (pos.z() & 15) << 8));
                }
            }
            if (i < a.size()) {
                union.addAll(a.get(i));
            }
            if (i < b.size()) {
                union.addAll(b.get(i));
            }
            merged.add(List.copyOf(union));
        }
        return merged;
    }
}
