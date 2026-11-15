package mycelium.merge;

import mycelium.chunk.BlockPos;
import mycelium.chunk.BlockState;
import mycelium.chunk.ChunkPos;
import mycelium.chunk.PalettedContainer;
import mycelium.chunk.Section;
import mycelium.nbt.CompoundTag;
import mycelium.nbt.Tag;
import mycelium.report.Category;
import mycelium.report.ReportEntry;
import mycelium.report.Severity;
import mycelium.rules.Rules;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;

/**
 * The 3-way merge of one section: block states and biome cells cell by cell, unknown fields as
 * whole values. Light is not merged; the chunk decides what happens to it.
 *
 * <p>For each cell: equal on both sides, take it; changed on one side, take that side; changed
 * on both to different values, ask the noise rules, and if none applies the newer side wins and
 * a conflict is recorded. Without a base, every difference between the sides is a conflict of
 * category {@code no_ancestor} unless a noise rule settles it.
 *
 * <p>The merged block states can still be changed by unit repair through {@link #take}.
 */
public final class SectionMerge {

    /**
     * The detail of the entry that records an egg of the base that neither side still has. The
     * entity merge reads these entries: what hatched on both sides is kept once.
     *
     * <p>A chunk that both sides store byte for byte alike is copied and never gets here. That
     * loses nothing: two worlds in which the egg hatched separately saved the chunk at different
     * times, so their chunks differ, and where the chunks are alike because one world was copied
     * from the other, what hatched is the same entity in both.
     */
    static final String HATCHED = "hatched or removed on both sides";

    private static final BlockState AIR = BlockState.of("minecraft:air");
    private static final String DEFAULT_BIOME = "minecraft:plains";

    private final int y;
    private final Section base;     // null when there is no ancestor
    private final Section ours;
    private final Section theirs;
    private PalettedContainer<BlockState> blocks;
    private byte[] origins;         // one Origin ordinal per cell; null while every cell is BASE
    private PalettedContainer<String> biomes;
    private CompoundTag extra;

    private SectionMerge(int y, Section base, Section ours, Section theirs) {
        this.y = y;
        this.base = base;
        this.ours = ours;
        this.theirs = theirs;
    }

    /**
     * A section the file does not have. The game creates it as air with the plains biome, so
     * that is what it merges as.
     */
    public static Section missing(int y) {
        return new Section(y, PalettedContainer.filled(Section.BLOCKS, AIR),
                PalettedContainer.filled(Section.BIOME_CELLS, DEFAULT_BIOME), null, null, CompoundTag.empty());
    }

    /**
     * Merges three versions of one section.
     *
     * @param base    the common ancestor, or null if there is none
     * @param chunk   the chunk the section belongs to, for report positions
     * @param newerPlayed whether the newer side's chunk differs from the base's at all, which it
     *                    does once a player was near it
     * @param entries receives what was resolved and what conflicted
     * @param unknown receives the ids of blocks that no block report knows
     */
    static SectionMerge merge(Section base, Section ours, Section theirs, ChunkPos chunk, MergeContext context,
                              BooleanSupplier newerPlayed, List<ReportEntry> entries, Set<String> unknown) {
        SectionMerge merge = new SectionMerge(ours.y(), base, ours, theirs);
        Side newer = context.precedence().newerIn(chunk);
        merge.mergeBlocks(chunk, context, newer, newerPlayed, entries);
        merge.mergeBiomes(chunk, context, newer, entries);
        merge.extra = Values.mergeCompound(base == null ? null : base.extra(), ours.extra(), theirs.extra(), newer,
                key -> entries.add(new ReportEntry(Severity.CONFLICT, Category.UNKNOWN_FIELD, context.dimension(),
                        new BlockPos(chunk.x() * 16, merge.y * 16, chunk.z() * 16),
                        text(base == null ? null : base.extra().get(key)), text(ours.extra().get(key)),
                        text(theirs.extra().get(key)), newer, "section field " + key)));
        for (Section section : new Section[] {base, ours, theirs}) {
            if (section != null) {
                for (BlockState state : section.blockStates().palette()) {
                    if (!context.rules().table().knows(state.name())) {
                        unknown.add(state.name());
                    }
                }
            }
        }
        return merge;
    }

    private static String text(Tag tag) {
        return tag == null ? null : tag.toString();
    }

    private void mergeBlocks(ChunkPos chunk, MergeContext context, Side newer, BooleanSupplier newerPlayed,
                             List<ReportEntry> entries) {
        PalettedContainer<BlockState> o = base == null ? null : base.blockStates();
        PalettedContainer<BlockState> a = ours.blockStates();
        PalettedContainer<BlockState> b = theirs.blockStates();
        if (o != null && a.equals(o) && b.equals(o)) {
            blocks = o;
            return;
        }
        Rules rules = context.rules();
        origins = new byte[Section.BLOCKS];
        List<BlockState> merged = new ArrayList<>(Section.BLOCKS);
        boolean sidesEqual = a.equals(b);
        // A plant taken whole from the newer side: what only the older side grew of it is left out.
        PalettedContainer<BlockState> older = newer == Side.OURS ? b : a;
        boolean whole = o != null && !sidesEqual && rules.hasNewerOnly()
                && older.palette().stream().anyMatch(state -> rules.newerOnly(state) != null) && newerPlayed.getAsBoolean();
        int leftOut = 0;
        BlockPos firstLeftOut = null;
        boolean eggs = o != null && o.palette().stream().anyMatch(state -> rules.hatch(state.name()) != null);
        for (int index = 0; index < Section.BLOCKS; index++) {
            BlockState was = o == null ? null : o.get(index);
            BlockState mine = a.get(index);
            BlockState other = sidesEqual ? mine : b.get(index);
            Origin origin;
            BlockState value;
            if (eggs && rules.hatch(was.name()) != null && !mine.name().equals(was.name()) && !other.name().equals(was.name())) {
                entries.add(new ReportEntry(Severity.RESOLVED, Category.BLOCK, context.dimension(), blockPos(chunk, index),
                        was.toString(), mine.toString(), other.toString(), null, HATCHED));
            }
            if (sidesEqual || mine.equals(other)) {
                value = mine;
                origin = mine.equals(was) ? Origin.BASE : Origin.BOTH;
            } else if (whole && rules.newerOnly(newer == Side.OURS ? other : mine) != null
                    && (newer == Side.OURS ? mine : other).equals(was)) {
                value = was;
                origin = Origin.BASE;
                firstLeftOut = leftOut++ == 0 ? blockPos(chunk, index) : firstLeftOut;
            } else if (mine.equals(was)) {
                value = other;
                origin = Origin.THEIRS;
            } else if (other.equals(was)) {
                value = mine;
                origin = Origin.OURS;
            } else {
                Rules.Noise noise = rules.noise(was, mine, other, newer);
                BlockPos pos = blockPos(chunk, index);
                if (noise != null) {
                    value = noise.state();
                    origin = Origin.of(noise.origin());
                    entries.add(new ReportEntry(Severity.RESOLVED, Category.BLOCK, context.dimension(), pos,
                            text(was), mine.toString(), other.toString(), noise.origin(), noise.rule()));
                } else {
                    value = newer == Side.OURS ? mine : other;
                    origin = Origin.of(newer);
                    entries.add(new ReportEntry(Severity.CONFLICT, was == null ? Category.NO_ANCESTOR : Category.BLOCK,
                            context.dimension(), pos, text(was), mine.toString(), other.toString(), newer, null));
                }
            }
            merged.add(value);
            origins[index] = (byte) origin.ordinal();
        }
        blocks = sidesEqual ? a : PalettedContainer.of(merged);
        if (leftOut > 0) {
            BlockState example = (newer == Side.OURS ? b : a).get(Section.blockIndex(firstLeftOut.x(), firstLeftOut.y(), firstLeftOut.z()));
            entries.add(new ReportEntry(Severity.RESOLVED, Category.BLOCK, context.dimension(), firstLeftOut,
                    null, null, null, newer, leftOut + " blocks of a plant that grew on " + newer.other().label()
                    + " left out, the first " + example.name() + ": it is taken whole from " + newer.label()));
        }
    }

    private void mergeBiomes(ChunkPos chunk, MergeContext context, Side newer, List<ReportEntry> entries) {
        PalettedContainer<String> o = base == null ? null : base.biomes();
        PalettedContainer<String> a = ours.biomes();
        PalettedContainer<String> b = theirs.biomes();
        if (a.equals(b)) {
            biomes = a;
            return;
        }
        if (o != null && (a.equals(o) || b.equals(o))) {
            biomes = a.equals(o) ? b : a;
            return;
        }
        List<String> merged = new ArrayList<>(Section.BIOME_CELLS);
        for (int index = 0; index < Section.BIOME_CELLS; index++) {
            String was = o == null ? null : o.get(index);
            String mine = a.get(index);
            String other = b.get(index);
            if (mine.equals(other) || other.equals(was)) {
                merged.add(mine);
            } else if (mine.equals(was)) {
                merged.add(other);
            } else {
                merged.add(newer == Side.OURS ? mine : other);
                BlockPos pos = new BlockPos(chunk.x() * 16 + (index & 3) * 4, y * 16 + (index >> 4) * 4,
                        chunk.z() * 16 + (index >> 2 & 3) * 4);
                entries.add(new ReportEntry(Severity.CONFLICT, was == null ? Category.NO_ANCESTOR : Category.BIOME,
                        context.dimension(), pos, was, mine, other, newer, was == null ? "biome" : null));
            }
        }
        biomes = PalettedContainer.of(merged);
    }

    private BlockPos blockPos(ChunkPos chunk, int index) {
        return new BlockPos(chunk.x() * 16 + (index & 15), y * 16 + (index >> 8), chunk.z() * 16 + (index >> 4 & 15));
    }

    private static String text(BlockState state) {
        return state == null ? null : state.toString();
    }

    public int y() {
        return y;
    }

    /** The merged block states. */
    public PalettedContainer<BlockState> blocks() {
        return blocks;
    }

    /** The merged biome cells. */
    public PalettedContainer<String> biomes() {
        return biomes;
    }

    /** The merged unknown fields. */
    public CompoundTag extra() {
        return extra;
    }

    /** Where the block in cell {@code index} came from. */
    public Origin origin(int index) {
        return origins == null ? Origin.BASE : Origin.values()[origins[index]];
    }

    /** One input's block state in cell {@code index}; {@code side} null asks for the base, which may be null. */
    public BlockState input(Side side, int index) {
        Section section = side == null ? base : side == Side.OURS ? ours : theirs;
        return section == null ? null : section.blockStates().get(index);
    }

    /** Sets cell {@code index} to {@code side}'s block. Used by unit repair. */
    void take(int index, Side side) {
        BlockState value = input(side, index);
        if (origins == null) {
            origins = new byte[Section.BLOCKS];
        }
        origins[index] = (byte) Origin.of(side).ordinal();
        if (!blocks.get(index).equals(value)) {
            List<BlockState> cells = blocks.toList();
            cells.set(index, value);
            blocks = PalettedContainer.of(cells);
        }
    }

    /** Sets cell {@code index} to a state that is neither side's. Used by unit repair. */
    void put(int index, BlockState value) {
        if (origins == null) {
            origins = new byte[Section.BLOCKS];
        }
        if (origins[index] == Origin.BASE.ordinal()) {
            origins[index] = (byte) Origin.BOTH.ordinal();   // no longer the base's block
        }
        if (!blocks.get(index).equals(value)) {
            List<BlockState> cells = blocks.toList();
            cells.set(index, value);
            blocks = PalettedContainer.of(cells);
        }
    }

    /**
     * The cells whose block did not come unchanged from the base. Without a base, the cells where
     * the sides differ: a block both sides generated alike was changed by neither.
     */
    Set<Integer> changedCells() {
        Set<Integer> changed = new TreeSet<>();
        if (origins != null) {
            for (int index = 0; index < Section.BLOCKS; index++) {
                if (origins[index] != Origin.BASE.ordinal() && (base != null || origins[index] != Origin.BOTH.ordinal())) {
                    changed.add(index);
                }
            }
        }
        return changed;
    }

    /** Whether the merged blocks are exactly {@code side}'s blocks. */
    boolean blocksEqual(Side side) {
        return blocks.equals((side == Side.OURS ? ours : theirs).blockStates());
    }

    /** The merged section, without light. */
    public Section section() {
        return new Section(y, blocks, biomes, null, null, extra);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SectionMerge that && y == that.y && blocks.equals(that.blocks)
                && biomes.equals(that.biomes) && extra.equals(that.extra);
    }

    @Override
    public int hashCode() {
        return Objects.hash(y, blocks, biomes, extra);
    }
}
