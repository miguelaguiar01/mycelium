package mycelium.cli;

import mycelium.merge.DimensionMerger;
import mycelium.merge.MergeFailedException;
import mycelium.merge.MergeRefusedException;
import mycelium.merge.Side;
import mycelium.merge.WorldMerger;
import mycelium.region.Compression;
import mycelium.report.MergeReport;
import mycelium.report.Severity;
import mycelium.rules.BlockTable;
import mycelium.rules.Rules;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The {@code mycelium-merge} command.
 *
 * <p>Exit codes: 0 merged with no true conflicts, 1 merged with true conflicts or refused chunks,
 * 2 refused (bad input, version mismatch), 3 internal error, which includes running out of memory.
 * After exit code 3 the output folder is incomplete.
 */
public final class Main {

    static final String USAGE = """
            usage: mycelium-merge --base <world O> --ours <world A> --theirs <world B> --newer ours|theirs
                                  --out <merged world> [--report <conflicts.json>]
                                  [--generated-base <fresh world from the same seed>]
                                  [--dimension <dimension id>|all] [--relight changed|neighbours|all]
                                  [--compression zlib|none|lz4]
                                  [--block-report <blocks.json>]... [--rules <rules.json>]...
            """;

    private Main() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    /** Runs the command and returns its exit code. */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        Map<String, String> single = new HashMap<>();
        List<Path> blockReports = new ArrayList<>();
        List<Path> rulesFiles = new ArrayList<>();
        try {
            for (int i = 0; i < args.length; i++) {
                String option = args[i];
                if (option.equals("--help") || option.equals("-h")) {
                    out.print(USAGE);
                    return 0;
                }
                if (!option.startsWith("--") || i + 1 >= args.length) {
                    throw new IllegalArgumentException("unexpected argument " + option);
                }
                String value = args[++i];
                switch (option) {
                    case "--block-report" -> blockReports.add(Path.of(value));
                    case "--rules" -> rulesFiles.add(Path.of(value));
                    case "--base", "--ours", "--theirs", "--newer", "--out", "--report", "--generated-base",
                         "--dimension", "--relight", "--compression" -> {
                        if (single.put(option, value) != null) {
                            throw new IllegalArgumentException(option + " given twice");
                        }
                    }
                    default -> throw new IllegalArgumentException("unknown option " + option);
                }
            }
            for (String required : new String[] {"--base", "--ours", "--theirs", "--newer", "--out"}) {
                if (!single.containsKey(required)) {
                    throw new IllegalArgumentException("missing " + required);
                }
            }
            Side newer = choice(single.get("--newer"), "--newer", Map.of("ours", Side.OURS, "theirs", Side.THEIRS));
            DimensionMerger.Relight relight = choice(single.getOrDefault("--relight", "changed"), "--relight", Map.of(
                    "changed", DimensionMerger.Relight.CHANGED, "neighbours", DimensionMerger.Relight.NEIGHBOURS,
                    "all", DimensionMerger.Relight.ALL));
            Compression compression = choice(single.getOrDefault("--compression", "zlib"), "--compression", Map.of(
                    "zlib", Compression.ZLIB, "none", Compression.NONE, "lz4", Compression.LZ4));
            String dimension = single.getOrDefault("--dimension", "all");

            BlockTable.Builder table = BlockTable.builder();
            for (Path file : blockReports) {
                table.addReport(Files.readString(file, StandardCharsets.UTF_8), file.toString());
            }
            Rules.Builder rules = Rules.builder(table.build());
            for (Path file : rulesFiles) {
                rules.addRules(Files.readString(file, StandardCharsets.UTF_8), file.toString());
            }

            WorldMerger.Options options = new WorldMerger.Options(
                    Path.of(single.get("--base")), Path.of(single.get("--ours")), Path.of(single.get("--theirs")), newer,
                    Path.of(single.get("--out")),
                    single.containsKey("--generated-base") ? Path.of(single.get("--generated-base")) : null,
                    dimension.equals("all") ? null : dimension, relight, compression, rules.build());
            MergeReport report;
            try {
                report = WorldMerger.merge(options);
            } catch (MergeFailedException e) {
                err.println("mycelium-merge: " + e.getMessage() + "; the output folder is incomplete");
                return 3;
            } catch (RuntimeException e) {
                // Past the options, a runtime exception is a fault of the tool, not of the command line.
                err.println("mycelium-merge: internal error; the output folder is incomplete");
                e.printStackTrace(err);
                return 3;
            }
            if (single.containsKey("--report")) {
                Path file = Path.of(single.get("--report")).toAbsolutePath();
                try {
                    Files.createDirectories(file.getParent());
                    Files.writeString(file, report.toJson(), StandardCharsets.UTF_8);
                } catch (IOException e) {
                    err.println("mycelium-merge: the merged world is written, but the report could not be: " + e);
                    return 3;
                }
            }
            long conflicts = report.count(Severity.CONFLICT);
            long refused = report.count(Severity.REFUSED);
            out.printf(Locale.ROOT, "merged into %s: %d conflicts, %d resolved, %d refused%s%n", single.get("--out"),
                    conflicts, report.count(Severity.RESOLVED), refused,
                    report.unknownBlocks().isEmpty() ? "" : ", unknown blocks " + report.unknownBlocks());
            if (report.chunksWithoutAncestor() > 0) {
                out.printf(Locale.ROOT, "note: %d chunks were generated by both sides and have no common ancestor. They were"
                        + " merged 2-way: where the sides differ, the newer side's block was taken and the other side's"
                        + " work there is lost. Pass --generated-base with a fresh world from the same seed that has"
                        + " these chunks to keep both sides' work.%n", report.chunksWithoutAncestor());
            }
            return conflicts + refused > 0 ? 1 : 0;
        } catch (IllegalArgumentException | MergeRefusedException e) {
            err.println("mycelium-merge: " + e.getMessage());
            if (e instanceof IllegalArgumentException) {
                err.print(USAGE);
            }
            return 2;
        } catch (IOException e) {
            err.println("mycelium-merge: " + e);
            return 2;
        } catch (OutOfMemoryError e) {
            err.println("mycelium-merge: out of memory with a heap limit of " + (Runtime.getRuntime().maxMemory() >> 20)
                    + " MB; the output folder is incomplete. Raise the limit with JAVA_OPTS=-Xmx<size>.");
            return 3;
        } catch (RuntimeException | Error e) {
            err.println("mycelium-merge: internal error; the output folder is incomplete");
            e.printStackTrace(err);
            return 3;
        }
    }

    private static <T> T choice(String value, String option, Map<String, T> choices) {
        T chosen = choices.get(value);
        if (chosen == null) {
            throw new IllegalArgumentException(option + " must be one of " + new java.util.TreeSet<>(choices.keySet())
                    + ", not " + value);
        }
        return chosen;
    }
}
