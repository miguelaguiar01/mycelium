package mycelium.merge;

import mycelium.chunk.Section;
import mycelium.report.ReportEntry;
import java.util.List;

/**
 * The outcome of merging one section.
 *
 * @param section the merged section, without light
 * @param origins where each of the 4,096 blocks came from, in cell order
 * @param entries what was resolved and what conflicted
 */
public record SectionMergeResult(Section section, List<Origin> origins, List<ReportEntry> entries) {

    public SectionMergeResult {
        origins = List.copyOf(origins);
        entries = List.copyOf(entries);
    }
}
