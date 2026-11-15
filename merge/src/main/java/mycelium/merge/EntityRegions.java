package mycelium.merge;

import mycelium.chunk.BlockPos;
import mycelium.chunk.ChunkData;
import mycelium.chunk.ChunkFormatException;
import mycelium.chunk.ChunkPos;
import mycelium.chunk.DataVersions;
import mycelium.chunk.Entities;
import mycelium.chunk.EntityChunk;
import mycelium.chunk.EntityChunkCodec;
import mycelium.chunk.EntityIndex;
import mycelium.chunk.FlatEntity;
import mycelium.merge.Storage.Handles;
import mycelium.merge.Storage.Store;
import mycelium.merge.Storage.Stores;
import mycelium.chunk.BlockState;
import mycelium.nbt.CompoundTag;
import mycelium.nbt.IntTag;
import mycelium.nbt.ListTag;
import mycelium.nbt.NbtIo;
import mycelium.nbt.StringTag;
import mycelium.nbt.Tag;
import mycelium.nbt.TagType;
import mycelium.rules.Rules;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

/**
 * Merges the entity files of a dimension.
 *
 * <p>Entities are merged as one set per dimension, because an entity can cross from any chunk
 * into any other. An entity chunk that is the same in all three worlds is left out of that set
 * and copied as stored: its entities are where they were. If a merged entity lands in such a
 * chunk, the chunk joins the merge after all, and it is the only one read again.
 *
 * <p>A side whose entity chunk or region file cannot be read counts as not having changed it:
 * the base's entities stand in for it, or the other side's if the base has none. Reading "no
 * entities" there would delete what the other side still has.
 */
final class EntityRegions {

    /** What one world stores at one position: its entities and the chunk's other tags, or why it cannot be read. */
    private record Slot(List<FlatEntity> entities, CompoundTag extra, String problem) {

        static final Slot NOTHING = new Slot(List.of(), CompoundTag.empty(), null);
    }

    /** The three versions at one position, after any side that cannot be read was given a stand-in. */
    private record Read(ChunkPos pos, List<FlatEntity> base, List<FlatEntity> ours, List<FlatEntity> theirs,
                        CompoundTag extra, List<ReportEntry> refused) {
    }

    private final String dimension;
    private final Stores stores;
    private final Stores blocks;
    private final WorldMerger.Options options;
    private final ExecutorService pool;
    private final MergeContext context;
    private final Map<RegionPos, byte[]> labels;
    /** The positions whose entities are merged. */
    private final Set<ChunkPos> active = new HashSet<>();
    private final List<FlatEntity> base = new ArrayList<>();
    private final List<FlatEntity> ours = new ArrayList<>();
    private final List<FlatEntity> theirs = new ArrayList<>();
    private final Map<ChunkPos, CompoundTag> extras = new HashMap<>();
    private final List<ReportEntry> refused = new ArrayList<>();
    private final Map<ChunkPos, Boolean> finishedInBase = new HashMap<>();
    private final Map<BlockPos, Optional<EntityMerger.Nest>> nests = new HashMap<>();
    private final Map<List<Object>, ListTag> blockEntities = new HashMap<>();
    private Set<UUID> copied;

    private EntityRegions(String dimension, Stores stores, Stores blocks, WorldMerger.Options options, ExecutorService pool,
                          MergeContext context) throws IOException {
        this.dimension = dimension;
        this.stores = stores;
        this.blocks = blocks;
        this.options = options;
        this.pool = pool;
        this.context = context;
        this.labels = Plan.compare(stores, pool);
    }

    /**
     * Merges and writes the entity files of one dimension.
     *
     * @param blocks  the dimension's chunk storage, to tell where the base has no finished chunk
     * @param players the entities in players' files
     */
    static void merge(String dimension, Path base, Path ours, Path theirs, Stores blocks, WorldMerger.Options options,
                      WorldMerger.Tuning tuning, ExecutorService pool, MergeContext context, MapIds maps,
                      PlayerEntities players, List<ReportEntry> entries) throws IOException {
        Stores stores = Stores.scan(base, ours, theirs, null, dimension, "entities", entries);
        new EntityRegions(dimension, stores, blocks, options, pool, context).run(tuning, maps, players, entries);
    }

    private void run(WorldMerger.Tuning tuning, MapIds maps, PlayerEntities players, List<ReportEntry> entries)
            throws IOException {
        // The positions whose entities are merged from the start: where a side differs from the base. That
        // takes in a chunk both sides changed alike. Its entities are the same on both sides, but what
        // both sides no longer have there is only known from the base: the cow one side killed and the
        // other led into the next chunk, the tadpole that became a frog on both sides and hopped away.
        Set<ChunkPos> first = new HashSet<>();
        labels.forEach((region, slots) -> {
            for (int slot = 0; slot < RegionPos.SLOTS; slot++) {
                if (slots[slot] >= Plan.QUIET || slots[slot] != Plan.NONE && !tuning.copyUnchanged()) {
                    first.add(new ChunkPos(region.chunkX(slot), region.chunkZ(slot)));
                }
            }
        });
        read(first);

        // The eggs of the base that neither side still has, which the block merge recorded.
        List<EntityMerger.Ending> hatched = new ArrayList<>();
        for (ReportEntry entry : entries) {
            if (entry.category() == Category.BLOCK && SectionMerge.HATCHED.equals(entry.detail()) && dimension.equals(entry.dimension())) {
                BlockState egg = context.rules().table().expand(BlockState.parse(entry.base()));
                Rules.Hatch hatch = context.rules().hatch(egg.name());
                hatched.add(new EntityMerger.Ending(entry.pos(), egg.name(), hatch.into(), hatch.count(egg)));
            }
        }

        SortedMap<ChunkPos, EntityChunk> merged = new TreeMap<>();
        List<ReportEntry> found = new ArrayList<>();
        boolean failed = false;
        try (Handles handles = new Handles()) {
            while (true) {
                found.clear();
                // An entity that a player took along is in the player's file, not in the entity files.
                List<FlatEntity> oursAll = new ArrayList<>(ours);
                List<FlatEntity> theirsAll = new ArrayList<>(theirs);
                List<FlatEntity> baseAll = new ArrayList<>(base);
                oursAll.addAll(players.carried(Side.OURS, dimension));
                theirsAll.addAll(players.carried(Side.THEIRS, dimension));
                baseAll.addAll(players.carried(null, dimension));
                EntityIndex oursRead = EntityIndex.of(oursAll);
                EntityIndex theirsRead = EntityIndex.of(theirsAll);
                EntityIndex oursIndex = maps.renumbers(Side.OURS) ? renumbered(oursRead, theirsRead, maps) : oursRead;
                EntityIndex theirsIndex = maps.renumbers(Side.THEIRS) ? renumbered(theirsRead, oursRead, maps) : theirsRead;
                for (Side side : Side.values()) {
                    for (UUID twice : (side == Side.OURS ? oursRead : theirsRead).duplicates()) {
                        found.add(new ReportEntry(Severity.RESOLVED, Category.ENTITY, dimension, null, null, null, null, null,
                                twice + " is stored twice on " + side.label() + "; one copy is kept"));
                    }
                }
                // Of the base, only the entities that a side still has matter.
                List<FlatEntity> known = new ArrayList<>();
                for (FlatEntity entity : baseAll) {
                    if (oursIndex.get(entity.uuid()) != null || theirsIndex.get(entity.uuid()) != null) {
                        known.add(entity);
                    }
                }
                try {
                    // What both sides ended: the eggs, and the creatures of the base that neither side has
                    // any more and that turn into another kind.
                    List<EntityMerger.Ending> endings = new ArrayList<>(hatched);
                    for (FlatEntity entity : baseAll) {
                        Set<String> becomes = context.rules().entityBecomes(entity.id());
                        if (becomes != null && oursIndex.get(entity.uuid()) == null && theirsIndex.get(entity.uuid()) == null) {
                            double[] at = Entities.positionOf(entity.data());
                            endings.add(new EntityMerger.Ending(new BlockPos((int) Math.floor(at[0]), (int) Math.floor(at[1]),
                                    (int) Math.floor(at[2])), entity.id() + " " + entity.uuid(), becomes, 1));
                        }
                    }
                    // The ones that become a single creature first: they say most about which creature it is.
                    endings.sort(Comparator.comparingInt(EntityMerger.Ending::count).thenComparing(EntityMerger.Ending::where)
                            .thenComparing(EntityMerger.Ending::what));
                    EntityMerger.Result result = EntityMerger.merge(EntityIndex.of(known), oursIndex, theirsIndex, context,
                            new EntityMerger.Surroundings(uuid -> players.isPlayer(uuid) || inCopiedChunk(uuid),
                                    players::holds, pos -> !finishedInBase(handles, pos), endings, pos -> nest(handles, pos)));
                    merged = players.settle(dimension, result.entities()).toChunks(DataVersions.MC_26_3);
                    found.addAll(result.entries());
                } catch (ChunkFormatException e) {
                    // Entity positions are checked when they are read, so this should not happen. If it does,
                    // each position keeps what the newer side stores there; nothing is thrown away.
                    players.unsettle(dimension);
                    failed = true;
                    found.add(new ReportEntry(Severity.REFUSED, Category.REFUSED, dimension, null, null, null, null,
                            options.newer(), "entities could not be merged; the entity chunks of " + options.newer().label()
                            + " are written where the sides differ: " + e.getMessage()));
                    break;
                }
                // A merged entity that lands in a chunk that would be copied makes that chunk join.
                Set<ChunkPos> joining = new HashSet<>();
                for (ChunkPos pos : merged.keySet()) {
                    if (label(pos) != Plan.NONE && !active.contains(pos)) {
                        joining.add(pos);
                    }
                }
                if (joining.isEmpty()) {
                    break;
                }
                read(joining);
            }
        }
        entries.addAll(refused);
        entries.addAll(found);
        write(merged, failed);
    }

    private byte label(ChunkPos pos) {
        byte[] slots = labels.get(RegionPos.ofChunk(pos.x(), pos.z()));
        return slots == null ? Plan.NONE : slots[RegionPos.slotIndex(pos.x(), pos.z())];
    }

    /** Reads the three versions at some positions and adds them to the merge. */
    private void read(Set<ChunkPos> positions) throws IOException {
        SortedMap<RegionPos, List<ChunkPos>> byRegion = new TreeMap<>();
        for (ChunkPos pos : new TreeSet<>(positions)) {
            byRegion.computeIfAbsent(RegionPos.ofChunk(pos.x(), pos.z()), key -> new ArrayList<>()).add(pos);
        }
        List<List<Read>> regions = Storage.parallel(pool, byRegion.values(), list -> {
            List<Read> reads = new ArrayList<>();
            try (Handles handles = new Handles()) {
                for (ChunkPos pos : list) {
                    reads.add(read(handles, pos));
                }
            }
            return reads;
        });
        for (List<Read> region : regions) {
            for (Read read : region) {
                base.addAll(read.base());
                ours.addAll(read.ours());
                theirs.addAll(read.theirs());
                if (!read.extra().isEmpty()) {
                    extras.put(read.pos(), read.extra());
                }
                refused.addAll(read.refused());
            }
        }
        active.addAll(positions);
    }

    private Read read(Handles handles, ChunkPos pos) {
        List<ReportEntry> problems = new ArrayList<>();
        Slot was = slot(handles, stores.base(), pos, "the base", problems);
        Slot mine = slot(handles, stores.ours(), pos, Side.OURS.label(), problems);
        Slot other = slot(handles, stores.theirs(), pos, Side.THEIRS.label(), problems);
        // A side that cannot be read counts as unchanged. An unreadable base is no ancestor.
        Slot oursSeen = mine.problem() == null ? mine : was.problem() == null && handles.has(stores.base(), pos) ? was
                : other.problem() == null ? other : Slot.NOTHING;
        Slot theirsSeen = other.problem() == null ? other : was.problem() == null && handles.has(stores.base(), pos) ? was
                : mine.problem() == null ? mine : Slot.NOTHING;
        Slot baseSeen = was.problem() == null ? was : Slot.NOTHING;
        CompoundTag extra = Values.mergeCompound(baseSeen.extra(), oursSeen.extra(), theirsSeen.extra(),
                context.precedence().newerIn(pos), key -> { });
        return new Read(pos, baseSeen.entities(), oursSeen.entities(), theirsSeen.entities(), extra, problems);
    }

    /** What a world stores at a position. A slot or region that cannot be read is recorded. */
    private Slot slot(Handles handles, Store store, ChunkPos pos, String world, List<ReportEntry> problems) {
        RegionPos region = RegionPos.ofChunk(pos.x(), pos.z());
        String problem;
        if (store.unreadable().contains(region)) {
            problem = "the region file cannot be read";
        } else if (!handles.has(store, pos)) {
            return Slot.NOTHING;
        } else if (RegionFile.decode(handles.raw(store, pos)) instanceof RegionSlot.Chunk chunk) {
            try {
                EntityChunk decoded = EntityChunkCodec.decode(chunk.root());
                return new Slot(Entities.flatten(decoded.entities()), decoded.extra(), null);
            } catch (ChunkFormatException e) {
                problem = e.getMessage();
            }
        } else {
            problem = ((RegionSlot.Corrupt) RegionFile.decode(handles.raw(store, pos))).reason();
        }
        problems.add(new ReportEntry(Severity.REFUSED, Category.REFUSED, dimension,
                new BlockPos(pos.x() * 16, 0, pos.z() * 16), null, null, null, null,
                store.file(region) + " slot " + RegionPos.slotIndex(pos.x(), pos.z()) + ": " + problem + "; " + world
                        + " counts as not having changed the entities there"));
        return new Slot(List.of(), CompoundTag.empty(), problem);
    }

    /**
     * Whether an entity is in a chunk that is copied. Only a leash can ask, so the copied chunks
     * are read the first time one does, and not at all in most merges.
     */
    private boolean inCopiedChunk(UUID uuid) {
        if (copied == null) {
            try {
                List<Set<UUID>> regions = Storage.parallel(pool, stores.regions(), region -> {
                    Set<UUID> found = new HashSet<>();
                    try (Handles handles = new Handles()) {
                        for (int slot = 0; slot < RegionPos.SLOTS; slot++) {
                            ChunkPos pos = new ChunkPos(region.chunkX(slot), region.chunkZ(slot));
                            if (!active.contains(pos) && handles.has(stores.ours(), pos)) {
                                slot(handles, stores.ours(), pos, Side.OURS.label(), new ArrayList<>()).entities()
                                        .forEach(entity -> found.add(entity.uuid()));
                            }
                        }
                    }
                    return found;
                });
                copied = new HashSet<>();
                regions.forEach(copied::addAll);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }
        return copied.contains(uuid);
    }

    /** Whether the base has a finished chunk at a position. Where it has none, each side generated the terrain. */
    private boolean finishedInBase(Handles handles, ChunkPos pos) {
        return finishedInBase.computeIfAbsent(pos, key -> handles.has(blocks.base(), pos)
                && RegionFile.decode(handles.raw(blocks.base(), pos)) instanceof RegionSlot.Chunk chunk
                && new StringTag(ChunkData.STATUS_FULL).equals(chunk.root().get("Status")));
    }

    /**
     * The nest at a position, if both sides changed it: then the merged world has the newer side's.
     * A nest only one side changed is that side's, and its bees need no rule of their own: the other
     * side never saw one leave or enter.
     */
    private EntityMerger.Nest nest(Handles handles, BlockPos pos) {
        return nests.computeIfAbsent(pos, key -> {
            String occupants = context.rules().hives().occupants();
            CompoundTag was = blockEntity(handles, blocks.base(), pos);
            CompoundTag mine = blockEntity(handles, blocks.ours(), pos);
            CompoundTag other = blockEntity(handles, blocks.theirs(), pos);
            if (was == null || mine == null || other == null || mine.equals(was) || other.equals(was)
                    || !(mine.get(occupants) instanceof ListTag inOurs) || !(other.get(occupants) instanceof ListTag inTheirs)) {
                return Optional.empty();
            }
            Side newer = context.precedence().newerIn(new ChunkPos(pos.x() >> 4, pos.z() >> 4));
            return Optional.of(new EntityMerger.Nest(newer, (newer == Side.OURS ? inOurs : inTheirs).size()));
        }).orElse(null);
    }

    /** The block entity a world stores at a position, or null if it has none or its chunk cannot be read. */
    private CompoundTag blockEntity(Handles handles, Store store, BlockPos pos) {
        ChunkPos chunk = new ChunkPos(pos.x() >> 4, pos.z() >> 4);
        ListTag stored = blockEntities.computeIfAbsent(List.of(store.directory(), chunk), key -> handles.has(store, chunk)
                && RegionFile.decode(handles.raw(store, chunk)) instanceof RegionSlot.Chunk read
                && read.root().get("block_entities") instanceof ListTag list ? list : ListTag.of(TagType.COMPOUND, List.of()));
        for (Tag element : stored) {
            if (element instanceof CompoundTag blockEntity && blockEntity.get("x") instanceof IntTag x && x.value() == pos.x()
                    && blockEntity.get("y") instanceof IntTag y && y.value() == pos.y()
                    && blockEntity.get("z") instanceof IntTag z && z.value() == pos.z()) {
                return blockEntity;
            }
        }
        return null;
    }

    /**
     * The entities of the side whose new maps are renumbered. An entity that is the same on both
     * sides is left as it is.
     */
    private static EntityIndex renumbered(EntityIndex index, EntityIndex other, MapIds maps) {
        List<FlatEntity> entities = new ArrayList<>();
        for (FlatEntity entity : index.entities().values()) {
            entities.add(EntityMerger.same(entity, other.get(entity.uuid())) ? entity : maps.apply(entity));
        }
        return EntityIndex.of(entities);
    }

    /**
     * Writes the entity files: the merged chunks at the positions that were merged, and the stored
     * chunk everywhere else.
     *
     * @param failed whether the entity merge failed, so that the newer side's chunks are written instead
     */
    private void write(SortedMap<ChunkPos, EntityChunk> merged, boolean failed) throws IOException {
        SortedSet<RegionPos> written = new TreeSet<>(stores.regions());
        merged.keySet().forEach(pos -> written.add(RegionPos.ofChunk(pos.x(), pos.z())));
        Storage.parallel(pool, written, region -> {
            SortedMap<Integer, RegionWriter.Record> records = new TreeMap<>();
            try (Handles handles = new Handles()) {
                for (int slot = 0; slot < RegionPos.SLOTS; slot++) {
                    ChunkPos pos = new ChunkPos(region.chunkX(slot), region.chunkZ(slot));
                    int timestamp = Math.max(handles.timestamp(stores.ours(), pos), handles.timestamp(stores.theirs(), pos));
                    RawSlot stored = handles.raw(stores.ours(), pos);
                    if (failed && active.contains(pos)) {
                        Side newer = context.precedence().newerIn(pos);
                        stored = handles.raw(stores.of(newer), pos) instanceof RawSlot.Stored
                                ? handles.raw(stores.of(newer), pos) : handles.raw(stores.of(newer.other()), pos);
                    } else if (label(pos) == Plan.NONE || active.contains(pos)) {
                        EntityChunk chunk = merged.get(pos);
                        CompoundTag extra = extras.getOrDefault(pos, CompoundTag.empty());
                        if (chunk != null || !extra.isEmpty()) {
                            // A chunk without entities is written only to carry tags the tool does not know.
                            List<CompoundTag> entities = chunk == null ? List.of() : chunk.entities();
                            records.put(slot, RegionWriter.Record.encode(timestamp, EntityChunkCodec.encode(
                                    new EntityChunk(DataVersions.MC_26_3, pos, entities, extra)),
                                    options.compression(), NbtIo.KeyOrder.SORTED));
                        }
                        continue;
                    }
                    if (stored instanceof RawSlot.Stored chunk) {
                        records.put(slot, RegionWriter.Record.copy(timestamp, chunk, options.compression()));
                    }
                }
            }
            if (!records.isEmpty()) {
                RegionWriter.write(Storage.folder(options.out(), dimension, "entities"), region, records);
            }
            return null;
        });
    }
}
