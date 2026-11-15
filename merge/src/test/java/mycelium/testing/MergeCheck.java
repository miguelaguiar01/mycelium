package mycelium.testing;

import mycelium.merge.DimensionMerger;
import mycelium.merge.Side;
import mycelium.merge.WorldMerger;
import mycelium.region.Compression;
import mycelium.report.MergeReport;
import mycelium.rules.BlockTable;
import mycelium.rules.Rules;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Merges three worlds several ways and checks that every way gives the same world and report.
 *
 * <p>The reference decodes and merges every chunk in whole regions on one thread. It is compared
 * with the merge as the tool divides it by default, and with the smallest squares on many
 * threads, which puts a border between merged pieces every four chunks. If a fourth world is
 * given, the reference is also compared with it; tools/mc uses that to compare with the output
 * of another build of the tool.
 *
 * <pre>./gradlew :merge:mergeCheck --args="&lt;base&gt; &lt;ours&gt; &lt;theirs&gt; ours|theirs changed|neighbours|all &lt;work folder&gt; [&lt;other merged world&gt;]"</pre>
 */
public final class MergeCheck {

    private MergeCheck() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 6) {
            System.err.println("usage: MergeCheck <base> <ours> <theirs> ours|theirs changed|neighbours|all <work folder> [<other merged world>]");
            System.exit(2);
        }
        Path work = Path.of(args[5]);
        Rules rules = Rules.vanilla(BlockTable.vanilla());
        DimensionMerger.Relight relight = DimensionMerger.Relight.valueOf(args[4].toUpperCase(Locale.ROOT));
        record Way(String name, WorldMerger.Tuning tuning) {
        }
        List<Way> ways = List.of(new Way("reference", new WorldMerger.Tuning(1, 32, false)),
                new Way("default", WorldMerger.Tuning.auto()),
                new Way("small", new WorldMerger.Tuning(Runtime.getRuntime().availableProcessors(), 4, true)));
        boolean failed = false;
        String expected = null;
        for (Way way : ways) {
            Path out = work.resolve(way.name());
            if (Files.exists(out)) {
                throw new IllegalArgumentException(out + " already exists");
            }
            long start = System.nanoTime();
            MergeReport report = WorldMerger.merge(new WorldMerger.Options(Path.of(args[0]), Path.of(args[1]), Path.of(args[2]),
                    args[3].equals("ours") ? Side.OURS : Side.THEIRS, out, null, null, relight, Compression.ZLIB, rules),
                    way.tuning());
            System.out.printf(Locale.ROOT, "%-9s %s: %.1f s, %d report entries%n", way.name(), way.tuning(),
                    (System.nanoTime() - start) / 1e9, report.entries().size());
            if (expected == null) {
                expected = report.toJson();
                continue;
            }
            if (!expected.equals(report.toJson())) {
                failed = true;
                System.out.println("  its report differs from the reference's");
            }
            failed |= show(CompareWorlds.differences(work.resolve("reference"), out));
        }
        if (args.length > 6) {
            System.out.println("against " + args[6]);
            failed |= show(CompareWorlds.differences(work.resolve("reference"), Path.of(args[6])));
        }
        System.exit(failed ? 1 : 0);
    }

    private static boolean show(List<String> differences) {
        differences.stream().limit(12).forEach(line -> System.out.println("  " + line));
        System.out.println(differences.isEmpty() ? "  the same world" : "  " + differences.size() + " chunks or files differ");
        return !differences.isEmpty();
    }
}
