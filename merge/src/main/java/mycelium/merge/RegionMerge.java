package mycelium.merge;

import mycelium.chunk.BlockPos;
import mycelium.chunk.ChunkCodec;
import mycelium.chunk.ChunkData;
import mycelium.chunk.ChunkFormatException;
import mycelium.chunk.ChunkPos;
import mycelium.merge.Storage.Handles;
import mycelium.merge.Storage.Store;
import mycelium.merge.Storage.Stores;
import mycelium.nbt.CompoundTag;
import mycelium.nbt.NbtIo;
import mycelium.region.Compression;
import mycelium.region.RawSlot;
import mycelium.region.RegionFile;
import mycelium.region.RegionPos;
import mycelium.region.RegionSlot;
import mycelium.region.RegionWriter;
import mycelium.report.Category;
import mycelium.report.ReportEntry;
import mycelium.report.Severity;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * Merges the chunks of one region and writes its region and POI files: square by square, each
 * either merged with what surrounds it or copied as stored, as the {@link Plan} says.
 */
final class RegionMerge {

    /** What merging one region added to the report. */
    record Result(List<ReportEntry> entries, SortedSet<String> unknownBlocks, int withoutAncestor) {
    }

    /** One version of a chunk: decoded, or the reason it cannot be. Both null if there is none. */
    private record Decoded(ChunkData chunk, String problem) {
    }

    /** A position that is not merged because an input cannot be read, and the stored chunk written instead. */
    private record Refusal(String problem, Side winner, RawSlot.Stored copy) {
    }

    private final RegionPos region;
    private final Stores stores;
    private final Stores poi;
    private final Plan plan;
    private final WorldMerger.Options options;
    private final WorldMerger.Tuning tuning;
    private final MergeContext context;
    private final MapIds maps;
    private final Handles handles = new Handles();
    private final SortedMap<Integer, RegionWriter.Record> records = new TreeMap<>();
    private final SortedMap<ChunkPos, DimensionMerger.Source> sources = new TreeMap<>();
    private final SortedMap<ChunkPos, List<ReportEntry>> entries = new TreeMap<>();
    private final SortedSet<String> unknownBlocks = new TreeSet<>();
    /** Where a side's chunk had map ids renumbered, so it is no longer the chunk as stored. */
    private final Set<ChunkPos> renumbered = new HashSet<>();
    private int withoutAncestor;

    RegionMerge(RegionPos region, Stores stores, Stores poi, Plan plan, WorldMerger.Options options,
                WorldMerger.Tuning tuning, MergeContext context, MapIds maps) {
        this.region = region;
        this.stores = stores;
        this.poi = poi;
        this.plan = plan;
        this.options = options;
        this.tuning = tuning;
        this.context = context;
        this.maps = maps;
    }

    Result run() throws IOException {
        try (handles) {
            int tile = tuning.tile();
            for (int z = region.z() * RegionPos.SIZE; z < (region.z() + 1) * RegionPos.SIZE; z += tile) {
                for (int x = region.x() * RegionPos.SIZE; x < (region.x() + 1) * RegionPos.SIZE; x += tile) {
                    mergeTile(x, z, x + tile - 1, z + tile - 1);
                }
            }
            // POI data describes a chunk's blocks. A chunk that took its blocks from one input keeps
            // that input's POI entry. A chunk that mixes both sides gets none, and the game rebuilds it.
            SortedMap<Integer, RegionWriter.Record> poiRecords = new TreeMap<>();
            for (Map.Entry<ChunkPos, DimensionMerger.Source> source : sources.entrySet()) {
                Store store = switch (source.getValue()) {
                    case BASE -> poi.base();
                    case OURS -> poi.ours();
                    case THEIRS -> poi.theirs();
                    case MERGED -> null;
                };
                ChunkPos pos = source.getKey();
                if (store != null && handles.raw(store, pos) instanceof RawSlot.Stored stored) {
                    poiRecords.put(RegionPos.slotIndex(pos.x(), pos.z()),
                            RegionWriter.Record.copy(stored.timestamp(), stored, options.compression()));
                }
            }
            if (!records.isEmpty()) {
                RegionWriter.write(Storage.folder(options.out(), context.dimension(), "region"), region, records);
            }
            if (!poiRecords.isEmpty()) {
                RegionWriter.write(Storage.folder(options.out(), context.dimension(), "poi"), region, poiRecords);
            }
        }
        List<ReportEntry> all = new ArrayList<>();
        entries.values().forEach(all::addAll);
        return new Result(all, unknownBlocks, withoutAncestor);
    }

    /** Merges or copies the chunks of the square from (x1, z1) to (x2, z2). */
    private void mergeTile(int x1, int z1, int x2, int z2) throws IOException {
        SortedSet<ChunkPos> own = new TreeSet<>();
        for (int z = z1; z <= z2; z++) {
            for (int x = x1; x <= x2; x++) {
                if (plan.label(x, z) != Plan.NONE) {
                    own.add(new ChunkPos(x, z));
                }
            }
        }
        if (own.isEmpty()) {
            return;
        }
        DimensionMerger.Relight relight = options.relight();
        SortedSet<ChunkPos> wanted = new TreeSet<>();
        if (!tuning.copyUnchanged()) {
            wanted.addAll(own);
        }
        if (relight == DimensionMerger.Relight.CHANGED || relight == DimensionMerger.Relight.ALL) {
            // With ALL every chunk loses its light. One that would be copied need not be merged for that:
            // its light is taken off as it is copied.
            own.stream().filter(pos -> plan.reached(pos.x(), pos.z())).forEach(wanted::add);
        } else if (relight == DimensionMerger.Relight.NEIGHBOURS) {
            // A chunk next to one whose blocks are mixed loses its light too. Whether a chunk's blocks
            // end up mixed is only known once it is merged, so the reachable chunks one step outside
            // the square are merged here as well, and their results dropped.
            for (int z = z1 - 1; z <= z2 + 1; z++) {
                for (int x = x1 - 1; x <= x2 + 1; x++) {
                    if (plan.reached(x, z)) {
                        wanted.add(new ChunkPos(x, z));
                        for (int dz = -1; dz <= 1; dz++) {
                            for (int dx = -1; dx <= 1; dx++) {
                                ChunkPos next = new ChunkPos(x + dx, z + dz);
                                if (own.contains(next)) {
                                    wanted.add(next);
                                }
                            }
                        }
                    }
                }
            }
        }
        if (!wanted.isEmpty()) {
            mergeWindow(wanted, own::contains, true);
        }
        for (ChunkPos pos : own) {
            if (!wanted.contains(pos)) {
                copy(pos);
            }
        }
    }

    /**
     * Merges the wanted positions together with what surrounds them, and keeps the results of
     * those that are {@code own}.
     */
    private void mergeWindow(SortedSet<ChunkPos> wanted, Predicate<ChunkPos> own, boolean withSurroundings)
            throws IOException {
        SortedSet<ChunkPos> around = new TreeSet<>();
        if (withSurroundings) {
            int halo = DimensionMerger.HALO;
            int x1 = wanted.stream().mapToInt(ChunkPos::x).min().orElseThrow() - halo;
            int x2 = wanted.stream().mapToInt(ChunkPos::x).max().orElseThrow() + halo;
            for (int z = wanted.first().z() - halo; z <= wanted.last().z() + halo; z++) {
                for (int x = x1; x <= x2; x++) {
                    // Only a changed chunk can take part in a repair. The reference way of dividing takes every
                    // chunk, so that it does not lean on the plan it is there to check.
                    boolean relevant = tuning.copyUnchanged() ? plan.changed(x, z) : plan.exists(x, z);
                    if (relevant && !wanted.contains(new ChunkPos(x, z))) {
                        around.add(new ChunkPos(x, z));
                    }
                }
            }
        }
        Map<ChunkPos, DimensionMerger.Inputs> loaded = new HashMap<>();
        Map<ChunkPos, Refusal> refused = new HashMap<>();
        DimensionMerger.Result result = DimensionMerger.merge(new DimensionMerger.Window() {
            @Override
            public SortedSet<ChunkPos> wanted() {
                return wanted;
            }

            @Override
            public SortedSet<ChunkPos> around() {
                return around;
            }

            @Override
            public DimensionMerger.Inputs load(ChunkPos pos) {
                return RegionMerge.this.load(pos, loaded, refused);
            }
        }, context, options.relight());

        Compression compression = options.compression();
        for (ChunkPos pos : wanted) {
            if (!own.test(pos)) {
                continue;
            }
            int slot = RegionPos.slotIndex(pos.x(), pos.z());
            Refusal refusal = refused.get(pos);
            if (refusal != null) {
                entries.computeIfAbsent(pos, key -> new ArrayList<>()).add(new ReportEntry(Severity.REFUSED,
                        Category.REFUSED, context.dimension(), new BlockPos(pos.x() * 16, 0, pos.z() * 16), null, null,
                        null, refusal.winner(), refusal.problem()));
                if (refusal.copy() != null) {
                    records.put(slot, RegionWriter.Record.copy(timestamp(pos), refusal.copy(), compression));
                }
                continue;
            }
            ChunkData chunk = result.chunks().get(pos);
            if (chunk == null) {
                continue;
            }
            // A chunk taken whole from one input is copied as that input stores it.
            DimensionMerger.Inputs inputs = loaded.get(pos);
            Store whole = !tuning.copyUnchanged() ? null : chunk == inputs.ours() ? stores.ours()
                    : chunk == inputs.theirs() ? stores.theirs() : chunk == inputs.base() ? stores.base() : null;
            boolean rewritten = renumbered.contains(pos) && (chunk == inputs.ours() ? maps.renumbers(Side.OURS)
                    : chunk == inputs.theirs() && maps.renumbers(Side.THEIRS));
            if (whole != null && !rewritten && handles.raw(whole, pos) instanceof RawSlot.Stored stored) {
                records.put(slot, RegionWriter.Record.copy(timestamp(pos), stored, compression));
            } else {
                records.put(slot, RegionWriter.Record.encode(timestamp(pos), ChunkCodec.encode(canonical(chunk)),
                        compression, NbtIo.KeyOrder.SORTED));
            }
            sources.put(pos, result.sources().get(pos));
            if (result.withoutAncestor().contains(pos)) {
                withoutAncestor++;
            }
        }
        for (ReportEntry entry : result.entries()) {
            ChunkPos pos = entry.pos().chunk();
            if (own.test(pos)) {
                entries.computeIfAbsent(pos, key -> new ArrayList<>()).add(entry);
            }
        }
        unknownBlocks.addAll(result.unknownBlocks());
    }

    /**
     * Decodes the versions of the chunk at a position. A version that is byte for byte another
     * one is decoded once. If any version cannot be read, the position is refused.
     */
    private DimensionMerger.Inputs load(ChunkPos pos, Map<ChunkPos, DimensionMerger.Inputs> loaded,
                                        Map<ChunkPos, Refusal> refused) {
        byte label = plan.label(pos.x(), pos.z());
        if (label == Plan.NONE) {
            return null;
        }
        RawSlot rawOurs = handles.raw(stores.ours(), pos);
        RawSlot rawTheirs = handles.raw(stores.theirs(), pos);
        Decoded base = decode(stores.base(), pos, handles.raw(stores.base(), pos));
        Decoded ours = label == Plan.THEIRS_CHANGED ? base : decode(stores.ours(), pos, rawOurs);
        boolean sidesAlike = label == Plan.QUIET || label == Plan.SAME;
        Decoded theirs = label == Plan.OURS_CHANGED ? base : sidesAlike ? ours : decode(stores.theirs(), pos, rawTheirs);
        String problem = base.problem() != null ? base.problem() : ours.problem() != null ? ours.problem() : theirs.problem();
        if (problem != null) {
            Side winner = null;
            RawSlot.Stored copy = null;
            for (Side side : new Side[] {options.newer(), options.newer().other()}) {
                Decoded decoded = side == Side.OURS ? ours : theirs;
                if (winner == null && decoded.chunk() != null
                        && (side == Side.OURS ? rawOurs : rawTheirs) instanceof RawSlot.Stored stored) {
                    winner = side;
                    copy = stored;
                }
            }
            if (winner == null && base.chunk() != null
                    && handles.raw(stores.base(), pos) instanceof RawSlot.Stored stored) {
                copy = stored;
            }
            refused.put(pos, new Refusal(problem, winner, copy));
            return null;
        }
        ChunkData generated = null;
        if ((base.chunk() == null || !base.chunk().isFull()) && handles.has(stores.generated(), pos)) {
            generated = decode(stores.generated(), pos, handles.raw(stores.generated(), pos)).chunk();
        }
        ChunkData oursChunk = ours.chunk();
        // The merge tells the sides apart by object, so equal sides are still two objects.
        ChunkData theirsChunk = sidesAlike && theirs.chunk() != null ? copyOf(theirs.chunk()) : theirs.chunk();
        // The older side's new maps are renumbered, unless both sides hold the very same chunk.
        if (!sidesAlike && label != Plan.THEIRS_CHANGED && maps.renumbers(Side.OURS)) {
            ChunkData moved = maps.apply(oursChunk);
            if (moved != oursChunk && !oursChunk.equals(theirsChunk)) {
                oursChunk = moved;
                renumbered.add(pos);
            }
        } else if (!sidesAlike && label != Plan.OURS_CHANGED && maps.renumbers(Side.THEIRS)) {
            ChunkData moved = maps.apply(theirsChunk);
            if (moved != theirsChunk && !theirsChunk.equals(oursChunk)) {
                theirsChunk = moved;
                renumbered.add(pos);
            }
        }
        DimensionMerger.Inputs inputs = new DimensionMerger.Inputs(base.chunk(), oursChunk, theirsChunk, generated);
        loaded.put(pos, inputs);
        return inputs;
    }

    private Decoded decode(Store store, ChunkPos pos, RawSlot raw) {
        if (raw instanceof RawSlot.Empty) {
            return new Decoded(null, null);
        }
        Path file = store.file(RegionPos.ofChunk(pos.x(), pos.z()));
        RegionSlot slot = RegionFile.decode(raw);
        if (slot instanceof RegionSlot.Corrupt corrupt) {
            return new Decoded(null, file + " slot " + RegionPos.slotIndex(pos.x(), pos.z()) + ": " + corrupt.reason());
        }
        try {
            ChunkData chunk = ChunkCodec.decode(((RegionSlot.Chunk) slot).root());
            return chunk.pos().equals(pos) ? new Decoded(chunk, null)
                    : new Decoded(null, file + ": chunk " + chunk.pos() + " is stored in the slot of " + pos);
        } catch (ChunkFormatException e) {
            return new Decoded(null, file + ": " + e.getMessage());
        }
    }

    /** Writes a chunk that the merge cannot change: the stored chunk of the side that has it. */
    private void copy(ChunkPos pos) throws IOException {
        byte label = plan.label(pos.x(), pos.z());
        if (label == Plan.SAME || label == Plan.QUIET) {
            // Both sides hold the same chunk, or neither holds one and the base's stays.
            if (handles.raw(stores.ours(), pos) instanceof RawSlot.Stored stored) {
                // If the base holds another chunk here, the sides' chunk wins only if it is finished:
                // an unfinished chunk does not replace a finished one. That takes a look inside.
                boolean wins = label == Plan.SAME || !handles.has(stores.base(), pos);
                ChunkData chunk = null;
                if (!wins) {
                    chunk = decode(stores.ours(), pos, stored).chunk();
                    wins = chunk != null && chunk.isFull();
                }
                if (wins) {
                    put(pos, stored, chunk, options.newer() == Side.OURS ? DimensionMerger.Source.OURS : DimensionMerger.Source.THEIRS);
                    return;
                }
            } else {
                if (handles.raw(stores.base(), pos) instanceof RawSlot.Stored stored) {
                    put(pos, stored, null, DimensionMerger.Source.BASE);
                }
                return;
            }
        }
        if (label == Plan.OURS_CHANGED || label == Plan.THEIRS_CHANGED) {
            Side side = label == Plan.OURS_CHANGED ? Side.OURS : Side.THEIRS;
            RawSlot changed = handles.raw(stores.of(side), pos);
            if (changed instanceof RawSlot.Empty) {
                // This side lacks a chunk the base has. The other side still holds the base's.
                if (handles.raw(stores.of(side.other()), pos) instanceof RawSlot.Stored stored) {
                    put(pos, stored, null, source(side.other()));
                }
                return;
            }
            // The other side's chunk is the base's, so this side's chunk is the result, unless it is
            // damaged or is an unfinished chunk where the base has a finished one.
            ChunkData chunk = decode(stores.of(side), pos, changed).chunk();
            if (chunk != null && (chunk.isFull() || !handles.has(stores.base(), pos))) {
                ChunkData moved = maps.renumbers(side) ? maps.apply(chunk) : chunk;
                if (moved == chunk) {
                    put(pos, (RawSlot.Stored) changed, chunk, source(side));
                } else {
                    // The chunk names maps that are renumbered, so it is written again.
                    write(pos, moved, source(side));
                }
                return;
            }
        }
        SortedSet<ChunkPos> alone = new TreeSet<>(List.of(pos));
        mergeWindow(alone, alone::contains, false);
    }

    /**
     * Writes a chunk as one input stores it. With {@code --relight all} a finished chunk is written
     * without its light instead, which the copy does not need a merge for.
     *
     * @param decoded the chunk, if it was decoded on the way here; else null
     */
    private void put(ChunkPos pos, RawSlot.Stored stored, ChunkData decoded, DimensionMerger.Source source) throws IOException {
        int slot = RegionPos.slotIndex(pos.x(), pos.z());
        if (options.relight() != DimensionMerger.Relight.ALL) {
            records.put(slot, RegionWriter.Record.copy(timestamp(pos), stored, options.compression()));
        } else if (decoded != null && decoded.isFull()) {
            write(pos, decoded, source);
            return;
        } else if (decoded != null) {
            // An unfinished chunk keeps its light.
            records.put(slot, RegionWriter.Record.copy(timestamp(pos), stored, options.compression()));
        } else {
            // The light comes off the stored tags; the blocks are not unpacked for it.
            CompoundTag root = RegionFile.decode(stored) instanceof RegionSlot.Chunk chunk ? chunk.root() : null;
            CompoundTag bare = root == null ? null : ChunkCodec.withoutDerivedData(root, pos);
            if (bare == null) {
                // Not a chunk this can be done to. Decoding it says what is wrong with it.
                SortedSet<ChunkPos> alone = new TreeSet<>(List.of(pos));
                mergeWindow(alone, alone::contains, false);
                return;
            }
            records.put(slot, bare == root ? RegionWriter.Record.copy(timestamp(pos), stored, options.compression())
                    : RegionWriter.Record.encode(timestamp(pos), bare, options.compression(), NbtIo.KeyOrder.SORTED));
        }
        sources.put(pos, source);
    }

    /** Writes a chunk that was decoded on the way: in canonical form, and with {@code --relight all} without its light. */
    private void write(ChunkPos pos, ChunkData chunk, DimensionMerger.Source source) throws IOException {
        ChunkData written = options.relight() == DimensionMerger.Relight.ALL && chunk.isFull()
                ? DimensionMerger.withoutDerivedData(chunk) : chunk;
        records.put(RegionPos.slotIndex(pos.x(), pos.z()), RegionWriter.Record.encode(timestamp(pos),
                ChunkCodec.encode(canonical(written)), options.compression(), NbtIo.KeyOrder.SORTED));
        sources.put(pos, source);
    }

    private static DimensionMerger.Source source(Side side) {
        return side == Side.OURS ? DimensionMerger.Source.OURS : DimensionMerger.Source.THEIRS;
    }

    /** The later of the two sides' save times, or the base's if neither side has the chunk. */
    private int timestamp(ChunkPos pos) {
        int timestamp = Math.max(handles.timestamp(stores.ours(), pos), handles.timestamp(stores.theirs(), pos));
        return timestamp == 0 ? handles.timestamp(stores.base(), pos) : timestamp;
    }

    private static ChunkData copyOf(ChunkData chunk) {
        return new ChunkData(chunk.dataVersion(), chunk.pos(), chunk.minSectionY(), chunk.status(), chunk.lastUpdate(),
                chunk.inhabitedTime(), chunk.sections(), chunk.blockEntities(), chunk.blockTicks(), chunk.fluidTicks(),
                chunk.heightmaps(), chunk.lightOn(), chunk.structures(), chunk.postProcessing(), chunk.extra());
    }

    /** The chunk with its block entities in position order, which is the canonical output order. */
    private static ChunkData canonical(ChunkData chunk) {
        Map<BlockPos, CompoundTag> sorted = new LinkedHashMap<>(new TreeMap<>(chunk.blockEntities()));
        return new ChunkData(chunk.dataVersion(), chunk.pos(), chunk.minSectionY(), chunk.status(), chunk.lastUpdate(),
                chunk.inhabitedTime(), chunk.sections(), sorted, chunk.blockTicks(), chunk.fluidTicks(),
                chunk.heightmaps(), chunk.lightOn(), chunk.structures(), chunk.postProcessing(), chunk.extra());
    }
}
