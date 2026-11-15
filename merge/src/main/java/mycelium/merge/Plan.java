package mycelium.merge;

import mycelium.chunk.ChunkPos;
import mycelium.merge.Storage.Handles;
import mycelium.merge.Storage.Stores;
import mycelium.region.RawSlot;
import mycelium.region.RegionPos;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * What comparing the stored chunks says about every chunk position of a dimension, and which
 * positions the merge can change. The comparison is byte for byte and decodes nothing.
 */
final class Plan {

    /** How the versions of the chunk at one position compare, byte for byte. */
    static final byte NONE = 0;             // no world has a chunk here
    static final byte SAME = 1;             // all three are the same
    static final byte QUIET = 2;            // ours and theirs are the same, and not the base's: both changed it alike
    static final byte OURS_CHANGED = 3;     // theirs is the base, ours is not
    static final byte THEIRS_CHANGED = 4;   // ours is the base, theirs is not
    static final byte BOTH = 5;             // all three differ, or a slot is corrupt

    /** Compares the three versions of every slot of every region, byte for byte. */
    static Map<RegionPos, byte[]> compare(Stores stores, ExecutorService pool) throws IOException {
        List<RegionPos> regions = stores.regions();
        List<byte[]> labels = Storage.parallel(pool, regions, region -> {
            byte[] slots = new byte[RegionPos.SLOTS];
            try (Handles handles = new Handles()) {
                for (int slot = 0; slot < RegionPos.SLOTS; slot++) {
                    ChunkPos pos = new ChunkPos(region.chunkX(slot), region.chunkZ(slot));
                    slots[slot] = compare(handles, stores, pos);
                }
            }
            return slots;
        });
        Map<RegionPos, byte[]> byRegion = new HashMap<>();
        for (int i = 0; i < regions.size(); i++) {
            byRegion.put(regions.get(i), labels.get(i));
        }
        return byRegion;
    }

    private static byte compare(Handles handles, Stores stores, ChunkPos pos) {
        boolean inBase = handles.has(stores.base(), pos);
        boolean inOurs = handles.has(stores.ours(), pos);
        boolean inTheirs = handles.has(stores.theirs(), pos);
        if (!inBase && !inOurs && !inTheirs) {
            return NONE;
        }
        RawSlot ours = inOurs ? handles.raw(stores.ours(), pos) : Storage.EMPTY;
        RawSlot theirs = inTheirs ? handles.raw(stores.theirs(), pos) : Storage.EMPTY;
        if (ours instanceof RawSlot.Corrupt || theirs instanceof RawSlot.Corrupt) {
            return BOTH;
        }
        RawSlot base = inBase ? handles.raw(stores.base(), pos) : Storage.EMPTY;
        if (base instanceof RawSlot.Corrupt) {
            return BOTH;
        }
        if (same(ours, theirs)) {
            return same(ours, base) ? SAME : QUIET;
        }
        return same(theirs, base) ? OURS_CHANGED : same(ours, base) ? THEIRS_CHANGED : BOTH;
    }

    /** Whether two slots hold the same chunk: both empty, or the same bytes once decompressed. */
    private static boolean same(RawSlot a, RawSlot b) {
        if (a instanceof RawSlot.Empty || b instanceof RawSlot.Empty) {
            return a instanceof RawSlot.Empty && b instanceof RawSlot.Empty;
        }
        if (!(a instanceof RawSlot.Stored first) || !(b instanceof RawSlot.Stored second)) {
            return false;
        }
        if (first.compression() == second.compression()) {
            // Different compressed bytes of the same content would only cost a merge that changes nothing.
            return first.sameBytes(second);
        }
        try {
            return java.util.Arrays.equals(first.nbt(), second.nbt());
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }


    private final Map<RegionPos, byte[]> labels;
    private final Map<RegionPos, boolean[]> reached = new HashMap<>();

    Plan(Map<RegionPos, byte[]> labels) {
        this.labels = labels;
        labels.forEach((region, slots) -> {
            for (int slot = 0; slot < RegionPos.SLOTS; slot++) {
                int x = region.chunkX(slot);
                int z = region.chunkZ(slot);
                if (meets(slots[slot], x, z)) {
                    for (int dx = -DimensionMerger.HALO; dx <= DimensionMerger.HALO; dx++) {
                        for (int dz = -DimensionMerger.HALO; dz <= DimensionMerger.HALO; dz++) {
                            RegionPos target = RegionPos.ofChunk(x + dx, z + dz);
                            if (label(x + dx, z + dz) != NONE) {
                                reached.computeIfAbsent(target, key -> new boolean[RegionPos.SLOTS])
                                        [RegionPos.slotIndex(x + dx, z + dz)] = true;
                            }
                        }
                    }
                }
            }
        });
    }

    /**
     * Whether changes of both sides meet in or next to this chunk. Only there can blocks of the
     * two sides end up side by side, which is what unit repair and the neighbour refresh act on.
     */
    private boolean meets(byte label, int x, int z) {
        if (label == BOTH) {
            return true;
        }
        if (label != OURS_CHANGED && label != THEIRS_CHANGED && label != QUIET) {
            return false;
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                byte next = label(x + dx, z + dz);
                // A chunk both sides changed alike holds changes of both, so it meets either side's.
                boolean other = label == QUIET ? next == OURS_CHANGED || next == THEIRS_CHANGED
                        : next == QUIET || next == (label == OURS_CHANGED ? THEIRS_CHANGED : OURS_CHANGED);
                if (next == BOTH || other) {
                    return true;
                }
            }
        }
        return false;
    }

    byte label(int x, int z) {
        byte[] slots = labels.get(RegionPos.ofChunk(x, z));
        return slots == null ? NONE : slots[RegionPos.slotIndex(x, z)];
    }

    /**
     * Whether the merge can give this chunk a result that is neither side's chunk as stored:
     * it has a chunk, and changes of both sides meet within {@link DimensionMerger#HALO} chunks.
     */
    boolean reached(int x, int z) {
        boolean[] slots = reached.get(RegionPos.ofChunk(x, z));
        return slots != null && slots[RegionPos.slotIndex(x, z)];
    }

    /** Whether a side differs from the base here, even if both differ from it alike. */
    boolean changed(int x, int z) {
        return label(x, z) >= QUIET;
    }

    /** Whether any world has a chunk here. */
    boolean exists(int x, int z) {
        return label(x, z) != NONE;
    }
}
