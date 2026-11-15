package mycelium.merge;

import mycelium.chunk.DataVersions;
import mycelium.merge.Storage.Stores;
import mycelium.nbt.CompoundTag;
import mycelium.nbt.IntTag;
import mycelium.nbt.NamedTag;
import mycelium.nbt.NbtIo;
import mycelium.region.Compression;
import mycelium.report.MergeReport;
import mycelium.report.ReportEntry;
import mycelium.rules.Rules;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Merges three world folders into a new one. This is the outer layer: it reads and writes files
 * and hands decoded chunks to the pure merge functions.
 *
 * <p>The work grows with what the two sides changed, not with the size of the world:
 *
 * <ol>
 * <li>Every stored chunk of ours, theirs and the base is compared byte for byte, without
 *     decoding it. That sorts the positions into: the same on both sides, changed by one side
 *     only, and changed by both.
 * <li>A chunk that is the same on both sides is copied as it is stored. A chunk only one side
 *     changed is decoded once, to check that it is sound, and then copied as stored. It is
 *     written again only if it names a map that is renumbered; see {@link MapIds}.
 * <li>The rest is decoded and merged: the chunks both sides changed, the chunks where one
 *     side's changes meet the other's, and every chunk within {@link DimensionMerger#HALO} chunks
 *     of those, because unit repair and the neighbour refresh can reach that far and no further.
 * </ol>
 *
 * <p>Merging is done in squares of a few chunks, several at a time, so memory use depends on the
 * square and the number of threads, not on the world. Neither changes the result: each square is
 * merged with every changed chunk around it that could affect it.
 *
 * <p>A chunk that cannot be merged, because a slot is corrupt or its inputs do not belong
 * together, is copied from the newer side that can be read, and recorded as refused. Damage
 * inside a chunk that is byte for byte the same on both sides is not looked for: such a chunk is
 * copied without being decoded.
 *
 * <p>The steps are in classes of their own: {@link Plan} compares, {@link RegionMerge} merges or
 * copies the chunks of one region, {@link EntityRegions} merges the entity files, and
 * {@link WorldFiles} everything outside region storage. {@link Storage} gives them the files.
 */
public final class WorldMerger {

    /**
     * @param base          the common ancestor, world O
     * @param ours          world A
     * @param theirs        world B
     * @param newer         the side that wins "newer side" rules
     * @param out           the folder to write; must not exist or must be empty
     * @param generatedBase a fresh world from the same seed, or null
     * @param dimension     a dimension id such as {@code minecraft:overworld}, or null for all
     * @param relight       which chunks lose their light so the game rebuilds it
     * @param compression   how chunks are written
     * @param rules         noise rules, unit definitions and the block table
     */
    public record Options(Path base, Path ours, Path theirs, Side newer, Path out, Path generatedBase,
                          String dimension, DimensionMerger.Relight relight, Compression compression, Rules rules) {
    }

    /**
     * How the work is divided. The merged world holds the same data whatever is chosen here.
     *
     * @param threads       how many squares are merged at the same time
     * @param tile          the side of a square in chunks: 4, 8, 16 or 32
     * @param copyUnchanged whether a chunk that needs no merging is copied as stored. If not, every
     *                      chunk is decoded and written again in canonical form
     */
    public record Tuning(int threads, int tile, boolean copyUnchanged) {

        /** Heap needed per chunk position of a square while it is merged, and per thread besides. */
        private static final long BYTES_PER_POSITION = 640 << 10;
        private static final long BYTES_PER_THREAD = 48 << 20;

        public Tuning {
            if (threads < 1 || tile != 4 && tile != 8 && tile != 16 && tile != 32) {
                throw new IllegalArgumentException("threads must be at least 1 and tile one of 4, 8, 16, 32");
            }
        }

        /** The fastest division that fits this JVM's heap limit and processors. */
        public static Tuning auto() {
            return fitting(Runtime.getRuntime().maxMemory(), Runtime.getRuntime().availableProcessors());
        }

        /** The fastest division for a heap limit in bytes and a number of processors, writing zlib. */
        public static Tuning fitting(long heapBytes, int processors) {
            return fitting(heapBytes, processors, Compression.ZLIB);
        }

        /**
         * The fastest division for a heap limit in bytes, a number of processors and the compression
         * the output is written with. A task holds the chunks of its region as they will be stored
         * until the region is complete, which is far more when they are not compressed.
         */
        public static Tuning fitting(long heapBytes, int processors, Compression compression) {
            long usable = heapBytes / 10 * 6;
            long perThread = BYTES_PER_THREAD + switch (compression) {
                case NONE -> 192L << 20;
                case LZ4 -> 48L << 20;
                case ZLIB, GZIP -> 0L;
            };
            Tuning best = new Tuning(1, 4, true);
            double bestSpeed = 0;
            for (int tile : new int[] {32, 16, 8, 4}) {
                // A square is merged together with the chunks around it that can affect it.
                long positions = (long) (tile + 2 * DimensionMerger.HALO) * (tile + 2 * DimensionMerger.HALO);
                int threads = (int) Math.min(processors, usable / (positions * BYTES_PER_POSITION + perThread));
                double speed = threads * (double) (tile * tile) / positions;
                if (threads >= 1 && speed > bestSpeed) {
                    best = new Tuning(threads, tile, true);
                    bestSpeed = speed;
                }
            }
            return best;
        }
    }

    private WorldMerger() {
    }

    /**
     * Runs the merge and writes the merged world, dividing the work to fit this JVM.
     *
     * @throws MergeRefusedException if the inputs cannot be merged at all: a world is missing or open
     *                               in the game, the output folder is in use, or versions or world-gen
     *                               settings differ. Nothing has been written
     * @throws MergeFailedException  if the merge stopped after it began to write. The output folder
     *                               is incomplete
     */
    public static MergeReport merge(Options options) throws IOException, MergeRefusedException {
        return merge(options, Tuning.fitting(Runtime.getRuntime().maxMemory(), Runtime.getRuntime().availableProcessors(),
                options.compression()));
    }

    /** Runs the merge and writes the merged world. When this returns or throws, no thread is still writing. */
    public static MergeReport merge(Options options, Tuning tuning) throws IOException, MergeRefusedException {
        // Everything that can refuse the merge is checked before anything is written.
        List<Path> inputs = List.of(options.base(), options.ours(), options.theirs());
        for (Path world : inputs) {
            if (!Files.isRegularFile(world.resolve("level.dat"))) {
                throw new MergeRefusedException(world + " is not a world folder: it has no level.dat");
            }
            requireVersion(world.resolve("level.dat"), "Data");
        }
        try (Stream<Path> existing = Files.exists(options.out()) ? Files.list(options.out()) : Stream.empty()) {
            if (existing.findAny().isPresent()) {
                throw new MergeRefusedException(options.out() + " exists and is not empty");
            }
        }
        String settings = "data/minecraft/world_gen_settings.dat";
        NamedTag wanted = readIfPresent(options.base().resolve(settings));
        for (Path world : List.of(options.ours(), options.theirs())) {
            if (!java.util.Objects.equals(wanted, readIfPresent(world.resolve(settings)))) {
                throw new MergeRefusedException("world-gen settings differ between " + options.base() + " and " + world);
            }
        }
        SortedSet<String> dimensions = dimensions(inputs);
        if (options.dimension() != null && !dimensions.contains(options.dimension())) {
            throw new MergeRefusedException("no dimension " + options.dimension() + " in the input worlds; found " + dimensions);
        }
        List<FileChannel> locks = lock(inputs);
        try {
            Files.createDirectories(options.out());
            try {
                return write(options, tuning, dimensions);
            } catch (IOException e) {
                throw new MergeFailedException(e);
            }
        } finally {
            for (FileChannel lock : locks) {
                lock.close();   // which gives the lock back
            }
        }
    }

    private static MergeReport write(Options options, Tuning tuning, SortedSet<String> dimensions) throws IOException {
        List<ReportEntry> entries = new ArrayList<>();
        SortedSet<String> unknownBlocks = new TreeSet<>();
        MapIds maps = MapIds.read(options.base(), options.ours(), options.theirs(), options.newer());
        PlayerEntities players = PlayerEntities.read(options.base(), options.ours(), options.theirs(), options.newer(),
                options.dimension());
        int withoutAncestor = 0;
        ExecutorService pool = Executors.newFixedThreadPool(tuning.threads(), work -> {
            Thread thread = new Thread(work, "mycelium-merge");
            thread.setDaemon(true);
            return thread;
        });
        try {
            for (String dimension : dimensions) {
                if (options.dimension() == null || options.dimension().equals(dimension)) {
                    withoutAncestor += mergeDimension(dimension, options, tuning, pool, maps, players, entries, unknownBlocks);
                } else {
                    copyDimension(dimension, options);
                }
            }
        } finally {
            // A failed task leaves others running. They must be done before the caller sees the folder.
            pool.shutdownNow();
            try {
                pool.awaitTermination(5, TimeUnit.MINUTES);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        new WorldFiles(options.base(), options.ours(), options.theirs(), options.out(), options.newer(), options.rules(),
                maps, players, dimensions, options.dimension(), entries).merge();
        return new MergeReport(DataVersions.MC_26_3, options.newer(), entries, unknownBlocks, withoutAncestor);
    }

    private static NamedTag readIfPresent(Path file) throws IOException {
        return Files.isRegularFile(file) ? NbtIo.readGzipFile(file) : null;
    }

    private static void requireVersion(Path file, String holder) throws IOException, MergeRefusedException {
        NamedTag root = NbtIo.readGzipFile(file);
        Object version = root.tag() instanceof CompoundTag compound && compound.get(holder) instanceof CompoundTag data
                ? data.get("DataVersion") : null;
        if (!(version instanceof IntTag number) || number.value() != DataVersions.MC_26_3) {
            throw new MergeRefusedException(file + " has data version " + version + "; only " + DataVersions.MC_26_3
                    + " (Minecraft 26.3) is supported");
        }
    }

    /**
     * Takes the lock the game holds on a world while it has it open. A world that is being played
     * changes under the merge, so it is refused.
     */
    private static List<FileChannel> lock(List<Path> worlds) throws IOException, MergeRefusedException {
        List<FileChannel> locks = new ArrayList<>();
        Set<Path> seen = new HashSet<>();
        try {
            for (Path world : worlds) {
                Path file = world.resolve("session.lock");
                // Not every copy of a world has the file, and a read-only one cannot be locked; neither is in use.
                if (!Files.isRegularFile(file) || !Files.isWritable(file) || !seen.add(file.toRealPath())) {
                    continue;
                }
                FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE);
                locks.add(channel);
                boolean free;
                try {
                    free = channel.tryLock() != null;
                } catch (OverlappingFileLockException e) {
                    free = false;   // held by this very JVM: the game the tool runs inside
                }
                if (!free) {
                    throw new MergeRefusedException(world + " is open in the game; close the world before merging it");
                }
            }
            return locks;
        } catch (IOException | MergeRefusedException | RuntimeException e) {
            for (FileChannel channel : locks) {
                channel.close();
            }
            throw e;
        }
    }

    /** The folders that make a dimension: its three kinds of storage and its data files. */
    private static final Set<String> DIMENSION_FOLDERS = Set.of("region", "entities", "poi", "data");

    /**
     * The ids of the dimensions found in any input, such as {@code minecraft:overworld}, sorted. A
     * dimension is a folder under {@code dimensions/<namespace>/} that holds storage or data. Its
     * path can have several parts, as in {@code mymod:worlds/mining}.
     */
    private static SortedSet<String> dimensions(List<Path> worlds) throws IOException {
        SortedSet<String> found = new TreeSet<>();
        for (Path world : worlds) {
            Path root = world.resolve("dimensions");
            if (Files.isDirectory(root)) {
                try (Stream<Path> namespaces = Files.list(root)) {
                    for (Path namespace : namespaces.filter(Files::isDirectory).toList()) {
                        dimensions(namespace, namespace, 0, found);
                    }
                }
            }
        }
        return found;
    }

    private static void dimensions(Path namespace, Path folder, int depth, SortedSet<String> found) throws IOException {
        List<Path> inside;
        try (Stream<Path> children = Files.list(folder)) {
            inside = children.filter(Files::isDirectory).sorted().toList();
        }
        boolean dimension = false;
        for (Path child : inside) {
            if (DIMENSION_FOLDERS.contains(child.getFileName().toString())) {
                dimension = true;
            } else if (depth < 16) {
                dimensions(namespace, child, depth + 1, found);
            }
        }
        if (dimension && depth > 0) {
            found.add(namespace.getFileName() + ":" + namespace.relativize(folder).toString().replace('\\', '/'));
        }
    }

    private static Path newerWorld(Options options) {
        return options.newer() == Side.OURS ? options.ours() : options.theirs();
    }

    /** Copies the storage of a dimension that is not merged from the newer side, file by file. */
    private static void copyDimension(String dimension, Options options) throws IOException {
        for (String storage : List.of("region", "entities", "poi")) {
            Path from = Storage.folder(newerWorld(options), dimension, storage);
            if (!Files.isDirectory(from)) {
                continue;
            }
            Path to = Storage.folder(options.out(), dimension, storage);
            Files.createDirectories(to);
            try (Stream<Path> files = Files.list(from)) {
                for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                    Files.copy(file, to.resolve(file.getFileName().toString()));
                }
            }
        }
    }

    /** @return the number of chunks that were merged without an ancestor */
    private static int mergeDimension(String dimension, Options options, Tuning tuning, ExecutorService pool,
                                      MapIds maps, PlayerEntities players, List<ReportEntry> entries,
                                      SortedSet<String> unknownBlocks) throws IOException {
        MergeContext context = new MergeContext(Precedence.always(options.newer()), options.rules(), dimension);
        Stores chunks = Stores.scan(options.base(), options.ours(), options.theirs(), options.generatedBase(), dimension,
                "region", entries);
        Stores poi = Stores.scan(options.base(), options.ours(), options.theirs(), null, dimension, "poi", null);
        Plan plan = new Plan(Plan.compare(chunks, pool));
        int withoutAncestor = 0;
        for (RegionMerge.Result result : Storage.parallel(pool, chunks.regions(),
                region -> new RegionMerge(region, chunks, poi, plan, options, tuning, context, maps).run())) {
            entries.addAll(result.entries());
            unknownBlocks.addAll(result.unknownBlocks());
            withoutAncestor += result.withoutAncestor();
        }
        EntityRegions.merge(dimension, options.base(), options.ours(), options.theirs(), chunks, options, tuning, pool,
                context, maps, players, entries);
        return withoutAncestor;
    }
}
