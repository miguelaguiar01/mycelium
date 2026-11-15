package mycelium.merge;

import mycelium.chunk.BlockPos;
import mycelium.chunk.BlockState;
import java.util.Collection;

/**
 * Marks blocks whose neighbour-derived state may be stale after a merge.
 *
 * <p>A fence, wall, pane, stair or redstone wire stores how it connects to the blocks next to
 * it. If two adjacent blocks end up with values that never stood side by side in any of the
 * three input worlds, such a state can be wrong. Both blocks are then listed in their chunk's
 * post-processing list, which makes the game recompute them from their neighbours, and let
 * fluids flow, when it loads the chunk. Verified in the game by tools/mc/probe_postprocessing.py.
 *
 * <p>Runs after unit repair, on the final blocks.
 */
public final class SeamRefresh {

    private static final int[][] NEIGHBOURS = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    private SeamRefresh() {
    }

    /** @return the number of blocks marked */
    public static int mark(Collection<ChunkMerge> merges, UnitRepair.Chunks chunks) {
        int marked = 0;
        for (ChunkMerge home : merges) {
            for (BlockPos pos : home.changedCells()) {
                BlockState here = home.merged(pos);
                for (int[] step : NEIGHBOURS) {
                    BlockPos next = new BlockPos(pos.x() + step[0], pos.y() + step[1], pos.z() + step[2]);
                    ChunkMerge away = next.chunk().equals(home.pos()) ? home : chunks.at(next.chunk());
                    BlockState there = away == null ? null : away.merged(next);
                    if (there == null || stoodTogether(home, pos, here, away, next, there)) {
                        continue;
                    }
                    home.refreshOnLoad(pos);
                    away.refreshOnLoad(next);
                    marked += 2;
                }
            }
        }
        return marked;
    }

    /** Whether some input world holds both merged values at these two positions. */
    private static boolean stoodTogether(ChunkMerge home, BlockPos pos, BlockState here,
                                         ChunkMerge away, BlockPos next, BlockState there) {
        for (Side side : new Side[] {null, Side.OURS, Side.THEIRS}) {
            if (here.equals(home.input(side, pos)) && there.equals(away.input(side, next))) {
                return true;
            }
        }
        return false;
    }
}
