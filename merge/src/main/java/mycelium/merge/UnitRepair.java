package mycelium.merge;

import mycelium.chunk.BlockPos;
import mycelium.chunk.BlockState;
import mycelium.chunk.ChunkPos;
import mycelium.report.Category;
import mycelium.report.ReportEntry;
import mycelium.report.Severity;
import mycelium.rules.Rules;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * Keeps multi-block units whole after the cell-by-cell merge.
 *
 * <p>Merging block by block can leave a door with its top half from one side and its bottom from
 * the other, or with one half replaced. This pass looks at every block that changed and at its
 * six neighbours. For each one that is part of a unit, it checks that the other half is in
 * place and that the halves do not come from opposite sides. A broken unit is taken whole from
 * one side: the side that changed it, or the newer side if both did. A unit that was already
 * broken in the base, or whose other half lies in a chunk that was not merged, is left alone.
 *
 * <p>Taking a unit from one side can break a neighbouring unit, so the pass repeats. From the
 * second round on, anything touching a repaired block is taken from the newer side, which makes
 * the result settle.
 *
 * <p>Each round decides from the blocks as they stand when it starts, and only then replaces
 * blocks. A block's fate in a round therefore depends only on blocks within three of it, and
 * after all rounds only on blocks within {@link #REACH}. That is what allows a dimension to be
 * merged in pieces: a piece that sees every changed block within that distance of its own
 * chunks gets the result the whole dimension would.
 */
public final class UnitRepair {

    private static final int MAX_ROUNDS = 8;
    /**
     * How far, in blocks, a changed block can affect the outcome for another block: three per
     * round, and once more for the look at what is still broken after the last round.
     */
    public static final int REACH = 3 * (MAX_ROUNDS + 1);
    private static final int[][] NEIGHBOURS = {{0, 0, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    /** Finds the merge that holds a chunk; null where the chunk was not merged cell by cell. */
    @FunctionalInterface
    public interface Chunks {
        ChunkMerge at(ChunkPos pos);
    }

    /**
     * A broken unit and the side it will be taken from.
     *
     * @param alone whether the block is made to stand alone instead: half a double chest whose
     *              other half is gone becomes a single chest and keeps what it holds
     */
    private record Decision(BlockPos member, ChunkMerge home, ChunkMerge away, Rules.UnitLink link, Side side,
                            boolean byBoth, boolean alone) {
    }

    private UnitRepair() {
    }

    /**
     * Repairs the units of the given chunk merges. Report entries are added to the merge of the
     * chunk that holds the unit's first block.
     *
     * @param merges the chunks to look for changed blocks in
     * @param chunks access to those merges and their neighbours by position
     * @return for each unit taken from one side, the chunk that holds its first block
     */
    public static List<ChunkPos> repair(Collection<ChunkMerge> merges, Chunks chunks, MergeContext context) {
        Rules rules = context.rules();
        SortedSet<BlockPos> candidates = new TreeSet<>();
        for (ChunkMerge merge : merges) {
            candidates.addAll(merge.changedCells());
        }
        Set<BlockPos> repaired = new HashSet<>();
        List<ChunkPos> repairs = new ArrayList<>();
        for (int round = 0; round <= MAX_ROUNDS && !candidates.isEmpty(); round++) {
            List<Decision> decisions = new ArrayList<>();
            Set<BlockPos> checked = new HashSet<>();
            for (BlockPos candidate : candidates) {
                for (int[] step : NEIGHBOURS) {
                    BlockPos member = new BlockPos(candidate.x() + step[0], candidate.y() + step[1], candidate.z() + step[2]);
                    if (!checked.add(member)) {
                        continue;
                    }
                    ChunkMerge home = chunks.at(member.chunk());
                    BlockState state = home == null ? null : home.merged(member);
                    Rules.UnitLink link = state == null ? null : rules.unit(state, member);
                    if (link == null) {
                        continue;
                    }
                    ChunkMerge away = chunks.at(link.partner().chunk());
                    BlockState partner = away == null ? null : away.merged(link.partner());
                    if (partner == null) {
                        continue;   // the other half is outside what was merged
                    }
                    Origin from = home.origin(member);
                    Origin partnerFrom = away.origin(link.partner());
                    boolean mixed = from == Origin.OURS && partnerFrom == Origin.THEIRS
                            || from == Origin.THEIRS && partnerFrom == Origin.OURS;
                    if (link.accepts().test(partner) && !mixed) {
                        continue;
                    }
                    boolean byOurs = changed(home, member, Side.OURS) || changed(away, link.partner(), Side.OURS);
                    boolean byTheirs = changed(home, member, Side.THEIRS) || changed(away, link.partner(), Side.THEIRS);
                    if (!byOurs && !byTheirs) {
                        continue;   // it was like this before either side played
                    }
                    boolean again = repaired.contains(member) || repaired.contains(link.partner());
                    Side side = byOurs && byTheirs || again ? home.newer() : byOurs ? Side.OURS : Side.THEIRS;
                    boolean alone = link.alone() != null && !link.accepts().test(partner);
                    decisions.add(new Decision(member, home, away, link, side, byOurs && byTheirs, alone));
                }
            }
            if (round == MAX_ROUNDS) {
                // Repairs kept breaking the next unit for all rounds. What is still broken stays so, and is said.
                for (Decision decision : decisions) {
                    BlockPos member = decision.member();
                    decision.home().report(new ReportEntry(Severity.CONFLICT, Category.UNIT, context.dimension(), member,
                            text(decision.home().input(null, member)), text(decision.home().input(Side.OURS, member)),
                            text(decision.home().input(Side.THEIRS, member)), null,
                            decision.link().rule() + ": left broken after " + MAX_ROUNDS + " rounds of repair"));
                }
                break;
            }
            SortedSet<BlockPos> touched = new TreeSet<>();
            for (Decision decision : decisions) {
                BlockPos member = decision.member();
                BlockPos partner = decision.link().partner();
                if (decision.alone()) {
                    if (decision.home().merged(member).equals(decision.link().alone())) {
                        continue;
                    }
                    decision.home().put(member, decision.link().alone());
                    repaired.add(member);
                    touched.add(member);
                    repairs.add(decision.home().pos());
                    decision.home().report(new ReportEntry(decision.byBoth() ? Severity.CONFLICT : Severity.RESOLVED,
                            Category.UNIT, context.dimension(), member, text(decision.home().input(null, member)),
                            text(decision.home().input(Side.OURS, member)), text(decision.home().input(Side.THEIRS, member)),
                            null, decision.link().rule() + ": its other half is gone, so it stands alone"));
                    continue;
                }
                boolean moved = take(decision.home(), member, decision.side()) | take(decision.away(), partner, decision.side());
                if (!moved) {
                    continue;
                }
                repaired.add(member);
                repaired.add(partner);
                touched.add(member);
                touched.add(partner);
                BlockPos first = member.compareTo(partner) <= 0 ? member : partner;
                ChunkMerge owner = first.equals(member) ? decision.home() : decision.away();
                repairs.add(owner.pos());
                owner.report(new ReportEntry(decision.byBoth() ? Severity.CONFLICT : Severity.RESOLVED,
                        Category.UNIT, context.dimension(), first, text(owner.input(null, first)),
                        text(owner.input(Side.OURS, first)), text(owner.input(Side.THEIRS, first)), decision.side(),
                        decision.link().rule()));
            }
            candidates = touched;
        }
        return repairs;
    }

    private static boolean changed(ChunkMerge merge, BlockPos pos, Side side) {
        BlockState base = merge.input(null, pos);
        BlockState value = merge.input(side, pos);
        if (base == null) {
            // No ancestor: a side counts as having changed the block if the sides differ.
            return !value.equals(merge.input(side.other(), pos));
        }
        return !value.equals(base);
    }

    private static boolean take(ChunkMerge merge, BlockPos pos, Side side) {
        if (merge.merged(pos).equals(merge.input(side, pos)) && merge.origin(pos) == Origin.of(side)) {
            return false;
        }
        boolean differs = !merge.merged(pos).equals(merge.input(side, pos));
        merge.take(pos, side);
        return differs;
    }

    private static String text(BlockState state) {
        return state == null ? null : state.toString();
    }
}
