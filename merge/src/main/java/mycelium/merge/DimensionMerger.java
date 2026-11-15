package mycelium.merge;

import mycelium.chunk.BlockPos;
import mycelium.chunk.ChunkData;
import mycelium.chunk.ChunkPos;
import mycelium.chunk.Section;
import mycelium.report.Category;
import mycelium.report.ReportEntry;
import mycelium.report.Severity;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Merges chunks of one dimension: decides what to do with each chunk position, merges the
 * positions that need it, repairs units across chunk borders and applies the relight mode.
 *
 * <p>Per position, with "missing" meaning absent or not fully generated:
 *
 * <table>
 * <caption>What is written for each combination of inputs</caption>
 * <tr><th>Base</th><th>Ours</th><th>Theirs</th><th>Result</th></tr>
 * <tr><td>any</td><td>finished</td><td>finished</td><td>3-way merge against the base, or against the
 *     generated base if the base is missing, or a 2-way merge if there is neither</td></tr>
 * <tr><td>any</td><td>finished</td><td>missing</td><td>ours</td></tr>
 * <tr><td>any</td><td>missing</td><td>finished</td><td>theirs</td></tr>
 * <tr><td>finished</td><td>missing</td><td>missing</td><td>the base</td></tr>
 * <tr><td>missing</td><td>missing</td><td>missing</td><td>the unfinished chunk of the side that
 *     changed it, or the newer side's if both did</td></tr>
 * </table>
 *
 * <p>A dimension can be merged whole or in pieces. A piece is a {@link Window}: the positions to
 * decide, and the positions around them whose changed blocks can reach them. Blocks only affect
 * each other across chunk borders through unit repair and the neighbour refresh, and both reach
 * less than {@link #HALO} chunks. So a window that names every chunk with changes within
 * {@code HALO} chunks of its wanted positions gives, for those positions, exactly the result of
 * merging the whole dimension at once.
 *
 * <p>This class is a pure function of its inputs.
 */
public final class DimensionMerger {

    /**
     * How many chunks away a changed block can affect the merged result of a chunk: unit repair
     * reaches {@link UnitRepair#REACH} blocks and the neighbour refresh one more.
     */
    public static final int HALO = (UnitRepair.REACH + 1 + 15) / 16;

    /** Which chunks lose heightmaps and light so the game rebuilds them. */
    public enum Relight {
        /** Only chunks whose merged blocks match neither side. */
        CHANGED,
        /** Those chunks and the eight chunks around each. */
        NEIGHBOURS,
        /** Every finished chunk. */
        ALL
    }

    /** Which input a written chunk's blocks are from. */
    public enum Source {
        BASE, OURS, THEIRS,
        /** A mix of both sides. Its heightmaps, light and POI entry are gone. */
        MERGED
    }

    /**
     * The versions of the chunk at one position. Any of them may be null.
     *
     * @param generated the chunk of a fresh world from the same seed, used as the ancestor if
     *                  there is no base
     */
    public record Inputs(ChunkData base, ChunkData ours, ChunkData theirs, ChunkData generated) {
    }

    /** The part of a dimension that one call to {@link #merge} works on. */
    public interface Window {

        /** The positions to decide. Each is in the result if any world has a chunk there. */
        SortedSet<ChunkPos> wanted();

        /**
         * Every other position within {@link #HALO} chunks of a wanted one where the sides differ
         * from each other or from the base. These chunks are merged so that their changed blocks
         * can take part in unit repair, but they are not in the result.
         */
        SortedSet<ChunkPos> around();

        /**
         * The versions of the chunk at a position, or null if no world has one or the position
         * must not be merged. Called at most once per position. Also called for positions that
         * are neither wanted nor around, when unit repair looks across a chunk border.
         */
        Inputs load(ChunkPos pos);
    }

    /**
     * The outcome for the wanted positions.
     *
     * @param chunks          every chunk to write, by position
     * @param entries         the report entries, in chunk order
     * @param unknownBlocks   ids of blocks that no block report knows
     * @param relit           positions of the chunks written without heightmaps and light
     * @param sources         for each chunk written, the input its blocks are from
     * @param unitRepairs     the number of multi-block units taken whole from one side
     * @param withoutAncestor the chunks finished on both sides that had no ancestor, not even in the
     *                        generated base, and in which a difference between the sides had to be
     *                        decided for the newer side
     */
    public record Result(SortedMap<ChunkPos, ChunkData> chunks, List<ReportEntry> entries,
                         SortedSet<String> unknownBlocks, SortedSet<ChunkPos> relit,
                         SortedMap<ChunkPos, Source> sources, int unitRepairs, SortedSet<ChunkPos> withoutAncestor) {
    }

    private DimensionMerger() {
    }

    /**
     * Merges a whole dimension held in memory.
     *
     * @param base          the common ancestor's chunks by position
     * @param ours          world A's chunks
     * @param theirs        world B's chunks
     * @param generatedBase chunks of a fresh world from the same seed, used as the ancestor where
     *                      the base has none; may be empty
     */
    public static Result merge(Map<ChunkPos, ChunkData> base, Map<ChunkPos, ChunkData> ours,
                               Map<ChunkPos, ChunkData> theirs, Map<ChunkPos, ChunkData> generatedBase,
                               MergeContext context, Relight relight) {
        SortedSet<ChunkPos> positions = new TreeSet<>(base.keySet());
        positions.addAll(ours.keySet());
        positions.addAll(theirs.keySet());
        return merge(new Window() {
            @Override
            public SortedSet<ChunkPos> wanted() {
                return positions;
            }

            @Override
            public SortedSet<ChunkPos> around() {
                return new TreeSet<>();
            }

            @Override
            public Inputs load(ChunkPos pos) {
                return new Inputs(base.get(pos), ours.get(pos), theirs.get(pos), generatedBase.get(pos));
            }
        }, context, relight);
    }

    /** Merges the wanted positions of a window. */
    public static Result merge(Window window, MergeContext context, Relight relight) {
        SortedMap<ChunkPos, ChunkData> output = new TreeMap<>();
        SortedMap<ChunkPos, Source> sources = new TreeMap<>();
        SortedMap<ChunkPos, ChunkMerge> merges = new TreeMap<>();      // of wanted positions
        // Merges of the positions around, and of positions unit repair asked for. Null if there is none.
        Map<ChunkPos, ChunkMerge> others = new HashMap<>();
        SortedMap<ChunkPos, List<ReportEntry>> entries = new TreeMap<>();
        Set<ChunkPos> twoWay = new HashSet<>();     // wanted positions merged without an ancestor
        SortedSet<ChunkPos> withoutAncestor = new TreeSet<>();
        for (ChunkPos pos : window.wanted()) {
            Inputs inputs = window.load(pos);
            if (inputs == null) {
                continue;
            }
            ChunkData o = inputs.base();
            ChunkData a = inputs.ours();
            ChunkData b = inputs.theirs();
            Side newer = context.precedence().newerIn(pos);
            boolean oursFull = a != null && a.isFull();
            boolean theirsFull = b != null && b.isFull();
            if (oursFull && theirsFull) {
                ChunkData ancestor = ancestor(inputs);
                try {
                    merges.put(pos, ChunkMerge.begin(ancestor, a, b, context));
                    if (ancestor == null) {
                        twoWay.add(pos);
                    }
                } catch (MergeRefusedException e) {
                    output.put(pos, newer == Side.OURS ? a : b);
                    entries.computeIfAbsent(pos, key -> new ArrayList<>()).add(new ReportEntry(
                            Severity.REFUSED, Category.REFUSED, context.dimension(),
                            new BlockPos(pos.x() * 16, 0, pos.z() * 16), null, null, null, newer, e.getMessage()));
                }
            } else if (oursFull || theirsFull) {
                output.put(pos, oursFull ? a : b);
            } else if (o != null && o.isFull()) {
                output.put(pos, o);
            } else {
                ChunkData unfinished = a == null ? b : b == null ? a
                        : a.equals(o) ? b : b.equals(o) ? a : newer == Side.OURS ? a : b;
                if (unfinished != null || o != null) {
                    output.put(pos, unfinished == null ? o : unfinished);
                }
            }
            ChunkData written = output.get(pos);
            if (written != null) {
                sources.put(pos, written == a ? Source.OURS : written == b ? Source.THEIRS : Source.BASE);
            }
        }
        for (ChunkPos pos : window.around()) {
            if (!merges.containsKey(pos) && !output.containsKey(pos)) {
                others.put(pos, begin(window.load(pos), context));
            }
        }
        UnitRepair.Chunks chunks = pos -> {
            ChunkMerge merge = merges.get(pos);
            if (merge != null || output.containsKey(pos)) {
                return merge;
            }
            if (!others.containsKey(pos)) {
                others.put(pos, window.wanted().contains(pos) ? null : begin(window.load(pos), context));
            }
            return others.get(pos);
        };

        int repairs = 0;
        for (ChunkPos owner : UnitRepair.repair(loaded(merges, others), chunks, context)) {
            repairs += merges.containsKey(owner) ? 1 : 0;
        }
        SeamRefresh.mark(loaded(merges, others), chunks);

        SortedSet<String> unknown = new TreeSet<>();
        SortedSet<ChunkPos> relit = new TreeSet<>();
        for (ChunkMerge merge : merges.values()) {
            ChunkMergeResult result = merge.finish();
            output.put(merge.pos(), result.chunk());
            unknown.addAll(result.unknownBlocks());
            if (!result.entries().isEmpty()) {
                entries.computeIfAbsent(merge.pos(), key -> new ArrayList<>()).addAll(result.entries());
            }
            // Chunks both sides generated alike differ only in the game's bookkeeping; nothing is lost there.
            if (twoWay.contains(merge.pos())
                    && result.entries().stream().anyMatch(entry -> entry.severity() == Severity.CONFLICT)) {
                withoutAncestor.add(merge.pos());
            }
            if (result.relit()) {
                relit.add(merge.pos());
            }
            sources.put(merge.pos(), result.blocksOf() == null ? Source.MERGED
                    : result.blocksOf() == Side.OURS ? Source.OURS : Source.THEIRS);
        }

        SortedSet<ChunkPos> alsoRelit = new TreeSet<>();
        if (relight == Relight.ALL) {
            alsoRelit.addAll(output.keySet());
        } else if (relight == Relight.NEIGHBOURS) {
            for (ChunkPos pos : relit) {
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        alsoRelit.add(new ChunkPos(pos.x() + dx, pos.z() + dz));
                    }
                }
            }
        }
        for (ChunkPos pos : alsoRelit) {
            ChunkData chunk = output.get(pos);
            if (chunk != null && chunk.isFull() && relit.add(pos)) {
                output.put(pos, withoutDerivedData(chunk));
            }
        }

        List<ReportEntry> all = new ArrayList<>();
        entries.values().forEach(all::addAll);
        return new Result(output, all, unknown, relit, sources, repairs, withoutAncestor);
    }

    /** The ancestor to merge two finished chunks against, or null if there is none. */
    private static ChunkData ancestor(Inputs inputs) {
        ChunkData base = inputs.base();
        if (base != null && base.isFull()) {
            return base;
        }
        return inputs.generated() != null && inputs.generated().isFull() ? inputs.generated() : null;
    }

    /** The merge of a chunk that is not wanted, or null if it is not finished on both sides. */
    private static ChunkMerge begin(Inputs inputs, MergeContext context) {
        if (inputs == null || inputs.ours() == null || inputs.theirs() == null
                || !inputs.ours().isFull() || !inputs.theirs().isFull()) {
            return null;
        }
        try {
            return ChunkMerge.begin(ancestor(inputs), inputs.ours(), inputs.theirs(), context);
        } catch (MergeRefusedException e) {
            return null;
        }
    }

    /** Every merge begun so far, in position order. */
    private static List<ChunkMerge> loaded(SortedMap<ChunkPos, ChunkMerge> merges, Map<ChunkPos, ChunkMerge> others) {
        SortedMap<ChunkPos, ChunkMerge> all = new TreeMap<>(merges);
        others.forEach((pos, merge) -> {
            if (merge != null) {
                all.put(pos, merge);
            }
        });
        return List.copyOf(all.values());
    }

    /**
     * The chunk without heightmaps, the {@code isLightOn} flag, light arrays and light-only
     * sections. The game rebuilds heightmaps on load and relights the chunk. The flag and the
     * arrays always go together: arrays alone are ignored, and the flag alone means "dark".
     */
    public static ChunkData withoutDerivedData(ChunkData chunk) {
        List<Section> sections = chunk.sections().stream()
                .filter(section -> !section.isLightOnly()).map(Section::withoutLight).toList();
        return new ChunkData(chunk.dataVersion(), chunk.pos(), chunk.minSectionY(), chunk.status(),
                chunk.lastUpdate(), chunk.inhabitedTime(), sections, chunk.blockEntities(), chunk.blockTicks(),
                chunk.fluidTicks(), null, null, chunk.structures(), chunk.postProcessing(), chunk.extra());
    }
}
