package mycelium.merge;

import mycelium.chunk.ChunkData;
import mycelium.report.ReportEntry;
import java.util.List;
import java.util.SortedSet;

/**
 * The outcome of merging one chunk.
 *
 * @param chunk         the merged chunk
 * @param entries       what was resolved and what conflicted, in the order found
 * @param unknownBlocks ids of blocks in the inputs that no block report knows
 * @param blocksOf      the side whose blocks the merged chunk has exactly, or null if it mixes both.
 *                      A mixed chunk has lost its heightmaps and light, and its POI entry must go too
 */
public record ChunkMergeResult(ChunkData chunk, List<ReportEntry> entries, SortedSet<String> unknownBlocks,
                               Side blocksOf) {

    /** Whether heightmaps and light were removed because the blocks match neither side. */
    public boolean relit() {
        return blocksOf == null;
    }

    public ChunkMergeResult {
        entries = List.copyOf(entries);
    }
}
