package mycelium.merge;

import mycelium.chunk.ChunkData;
import mycelium.chunk.Section;
import mycelium.report.ReportEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/** The library entry points for merging single chunks and sections. All are pure functions. */
public final class Merger {

    private Merger() {
    }

    /**
     * Merges three versions of one finished chunk, without unit repair, which needs the
     * neighbouring chunks.
     *
     * @param base the common ancestor, or null if there is none
     * @throws MergeRefusedException if the inputs are unfinished or disagree on version, position or height
     */
    public static ChunkMergeResult mergeChunk(ChunkData base, ChunkData ours, ChunkData theirs, MergeContext context)
            throws MergeRefusedException {
        return ChunkMerge.begin(base, ours, theirs, context).finish();
    }

    /**
     * Merges three versions of one section. The result carries no light.
     *
     * @param base  the common ancestor, or null if there is none
     * @param chunk the chunk the section is in, for report positions and precedence
     */
    public static SectionMergeResult mergeSection(Section base, Section ours, Section theirs,
                                                  mycelium.chunk.ChunkPos chunk,
                                                  MergeContext context) {
        List<ReportEntry> entries = new ArrayList<>();
        Section newer = context.precedence().newerIn(chunk) == Side.OURS ? ours : theirs;
        SectionMerge merge = SectionMerge.merge(base, ours, theirs, chunk, context,
                () -> base != null && !newer.equals(base), entries, new TreeSet<>());
        List<Origin> origins = new ArrayList<>(Section.BLOCKS);
        for (int index = 0; index < Section.BLOCKS; index++) {
            origins.add(merge.origin(index));
        }
        return new SectionMergeResult(merge.section(), origins, entries);
    }
}
