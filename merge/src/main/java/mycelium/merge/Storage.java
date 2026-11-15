package mycelium.merge;

import mycelium.chunk.ChunkPos;
import mycelium.region.RawSlot;
import mycelium.region.RegionFile;
import mycelium.region.RegionPos;
import mycelium.report.Category;
import mycelium.report.ReportEntry;
import mycelium.report.Severity;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.stream.Stream;

/**
 * The region files of the worlds being merged: where they are, the handles a task holds on them,
 * and the pool that runs one task per region.
 */
final class Storage {

    static final RawSlot EMPTY = new RawSlot.Empty();

    private Storage() {
    }

    /** The folder of one kind of storage, such as {@code region}, of a dimension of a world. */
    static Path folder(Path world, String dimension, String storage) {
        String[] id = dimension.split(":", 2);
        return world.resolve("dimensions").resolve(id[0]).resolve(id[1]).resolve(storage);
    }

    /**
     * The region files of one storage folder of one world.
     *
     * @param regions    the files that can be read
     * @param unreadable the files whose header cannot be read. What they hold is unknown, which is
     *                   not the same as holding nothing
     */
    record Store(Path directory, Set<RegionPos> regions, Set<RegionPos> unreadable) {

        /**
         * Lists the region files of a folder. A file whose header cannot be read is kept apart and,
         * if {@code refused} is given, recorded there.
         */
        static Store scan(Path directory, String dimension, List<ReportEntry> refused) throws IOException {
            Set<RegionPos> regions = new HashSet<>();
            Set<RegionPos> unreadable = new HashSet<>();
            if (directory != null && Files.isDirectory(directory)) {
                try (Stream<Path> files = Files.list(directory)) {
                    for (Path file : files.sorted().toList()) {
                        RegionPos pos = RegionPos.parse(file.getFileName().toString());
                        if (pos == null) {
                            continue;
                        }
                        try (RegionFile region = RegionFile.open(file)) {
                            regions.add(region.pos());
                        } catch (IOException e) {
                            unreadable.add(pos);
                            if (refused != null) {
                                refused.add(new ReportEntry(Severity.REFUSED, Category.REFUSED, dimension, null, null, null,
                                        null, null, file + ": " + e.getMessage()));
                            }
                        }
                    }
                }
            }
            return new Store(directory, regions, unreadable);
        }

        Path file(RegionPos pos) {
            return directory.resolve(pos.fileName());
        }
    }

    /** One storage folder, such as {@code region}, in each of the worlds. */
    record Stores(Store base, Store ours, Store theirs, Store generated) {

        static Stores scan(Path base, Path ours, Path theirs, Path generated, String dimension, String storage,
                           List<ReportEntry> refused) throws IOException {
            return new Stores(Store.scan(folder(base, dimension, storage), dimension, refused),
                    Store.scan(folder(ours, dimension, storage), dimension, refused),
                    Store.scan(folder(theirs, dimension, storage), dimension, refused),
                    Store.scan(generated == null ? null : folder(generated, dimension, storage), dimension, null));
        }

        Store of(Side side) {
            return side == Side.OURS ? ours : theirs;
        }

        /** Every region that the base, ours or theirs has, in order. */
        List<RegionPos> regions() {
            SortedSet<RegionPos> all = new TreeSet<>(base.regions());
            all.addAll(ours.regions());
            all.addAll(theirs.regions());
            return List.copyOf(all);
        }
    }

    /** The region files one task has open. Chunks are read from them one at a time. */
    static final class Handles implements AutoCloseable {

        private final Map<Path, RegionFile> open = new HashMap<>();

        private RegionFile region(Store store, ChunkPos pos) {
            RegionPos region = RegionPos.ofChunk(pos.x(), pos.z());
            if (!store.regions().contains(region)) {
                return null;
            }
            return open.computeIfAbsent(store.file(region), file -> {
                try {
                    return RegionFile.open(file);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }

        RawSlot raw(Store store, ChunkPos pos) {
            RegionFile region = region(store, pos);
            return region == null ? EMPTY : region.raw(RegionPos.slotIndex(pos.x(), pos.z()));
        }

        boolean has(Store store, ChunkPos pos) {
            RegionFile region = region(store, pos);
            return region != null && region.has(RegionPos.slotIndex(pos.x(), pos.z()));
        }

        int timestamp(Store store, ChunkPos pos) {
            RegionFile region = region(store, pos);
            return region == null ? 0 : region.timestamp(RegionPos.slotIndex(pos.x(), pos.z()));
        }

        @Override
        public void close() throws IOException {
            for (RegionFile region : open.values()) {
                region.close();
            }
        }
    }

    /** Runs {@code work} on each item, several at a time, and returns the results in the items' order. */
    static <T, R> List<R> parallel(ExecutorService pool, Collection<T> items, Work<T, R> work) throws IOException {
        List<Future<R>> futures = new ArrayList<>();
        for (T item : items) {
            futures.add(pool.submit(() -> work.on(item)));
        }
        List<R> results = new ArrayList<>();
        try {
            for (Future<R> future : futures) {
                results.add(future.get());
            }
        } catch (ExecutionException e) {
            futures.forEach(future -> future.cancel(true));
            Throwable cause = e.getCause();
            if (cause instanceof UncheckedIOException unchecked) {
                throw unchecked.getCause();
            }
            if (cause instanceof IOException io) {
                throw io;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(cause);
        } catch (InterruptedException e) {
            futures.forEach(future -> future.cancel(true));
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
        return results;
    }

    @FunctionalInterface
    interface Work<T, R> {
        R on(T item) throws IOException;
    }
}
