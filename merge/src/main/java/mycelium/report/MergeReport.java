package mycelium.report;

import mycelium.chunk.BlockPos;
import mycelium.json.Json;
import mycelium.merge.Side;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SortedSet;

/**
 * The conflict report of one merge.
 *
 * @param dataVersion   the data version of the merged worlds
 * @param newer         the side that won "newer side" rules
 * @param entries       every entry, sorted by dimension, category, position and detail
 * @param unknownBlocks ids of blocks found in the worlds that no block report knows
 * @param chunksWithoutAncestor the number of chunks finished on both sides that had no ancestor and
 *                      were merged 2-way, where every difference goes to the newer side
 */
public record MergeReport(int dataVersion, Side newer, List<ReportEntry> entries, SortedSet<String> unknownBlocks,
                          int chunksWithoutAncestor) {

    private static final Comparator<ReportEntry> ORDER = Comparator
            .comparing((ReportEntry entry) -> entry.dimension() == null ? "" : entry.dimension())
            .thenComparing(entry -> entry.category().name())
            .thenComparing(ReportEntry::pos, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(entry -> entry.detail() == null ? "" : entry.detail())
            .thenComparing(entry -> entry.severity().name());

    public MergeReport {
        List<ReportEntry> sorted = new ArrayList<>(entries);
        sorted.sort(ORDER);
        entries = List.copyOf(sorted);
    }

    public long count(Severity severity) {
        return entries.stream().filter(entry -> entry.severity() == severity).count();
    }

    /** The report as JSON, in the layout of CHUNK-MERGE-SPEC.md. */
    public String toJson() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("dataVersion", dataVersion);
        root.put("newer", newer.label());
        Map<String, Object> summary = new LinkedHashMap<>();
        for (Severity severity : Severity.values()) {
            summary.put(severity.name().toLowerCase(Locale.ROOT), count(severity));
        }
        root.put("summary", summary);
        root.put("unknownBlocks", List.copyOf(unknownBlocks));
        root.put("chunksWithoutAncestor", chunksWithoutAncestor);
        List<Object> list = new ArrayList<>();
        for (ReportEntry entry : entries) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("severity", entry.severity().name().toLowerCase(Locale.ROOT));
            item.put("category", entry.category().name().toLowerCase(Locale.ROOT));
            putIfPresent(item, "dimension", entry.dimension());
            BlockPos pos = entry.pos();
            if (pos != null) {
                item.put("pos", List.of(pos.x(), pos.y(), pos.z()));
            }
            putIfPresent(item, "base", entry.base());
            putIfPresent(item, "ours", entry.ours());
            putIfPresent(item, "theirs", entry.theirs());
            putIfPresent(item, "winner", entry.winner() == null ? null : entry.winner().label());
            putIfPresent(item, "detail", entry.detail());
            list.add(item);
        }
        root.put("entries", list);
        return Json.write(root);
    }

    private static void putIfPresent(Map<String, Object> item, String key, String value) {
        if (value != null) {
            item.put(key, value);
        }
    }
}
