package mycelium.report;

import mycelium.chunk.BlockPos;
import mycelium.merge.Side;
import java.util.Objects;

/**
 * One line of the conflict report.
 *
 * @param dimension the dimension id, or null for entries about the world as a whole
 * @param pos       the block position, or the lowest corner of the chunk or cell concerned; may be null
 * @param base      the common ancestor's value as text, or null
 * @param ours      world A's value as text, or null
 * @param theirs    world B's value as text, or null
 * @param winner    the side whose value was written, or null if neither side's value was
 * @param detail    the rule that applied, the field concerned, or the reason for a refusal
 */
public record ReportEntry(Severity severity, Category category, String dimension, BlockPos pos,
                          String base, String ours, String theirs, Side winner, String detail) {

    public ReportEntry {
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(category, "category");
    }
}
