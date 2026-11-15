package mycelium.merge;

import static mycelium.testing.TestChunks.context;
import static mycelium.testing.TestChunks.set;
import static org.junit.jupiter.api.Assertions.assertEquals;

import mycelium.chunk.ChunkData;
import mycelium.chunk.ChunkPos;
import mycelium.report.ReportEntry;
import mycelium.testing.TestChunks;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.SortedSet;
import java.util.TreeSet;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.statistics.Statistics;

/**
 * A dimension merged one chunk at a time, each with the changed chunks around it, must come out
 * as when it is merged all at once. The worlds here are strips of doors, beds and chests placed
 * at random by both sides across chunk borders, so that unit repair has chains to follow.
 */
class DividedMergePropertyTest {

    private static final int WIDTH = 6;
    private static final int DEPTH = 2;
    private static final String[] STATES = {
        "minecraft:air", "minecraft:stone", "minecraft:oak_fence", "minecraft:oak_door",
        "minecraft:oak_door[facing=north,half=upper,hinge=left,open=false,powered=false]",
        "minecraft:red_bed", "minecraft:red_bed[facing=north,occupied=false,part=head]",
        "minecraft:red_bed[facing=east,occupied=false,part=foot]", "minecraft:red_bed[facing=east,occupied=false,part=head]",
        "minecraft:red_bed[facing=west,occupied=false,part=foot]", "minecraft:red_bed[facing=west,occupied=false,part=head]",
        "minecraft:chest[facing=north,type=left,waterlogged=false]", "minecraft:chest[facing=north,type=right,waterlogged=false]",
    };

    /** The world with blocks placed at random in a band four blocks deep along the border between the two rows of chunks. */
    private static Map<ChunkPos, ChunkData> play(Map<ChunkPos, ChunkData> world, Random random, int edits) {
        Map<ChunkPos, ChunkData> played = new HashMap<>(world);
        for (int i = 0; i < edits; i++) {
            int x = random.nextInt(WIDTH * 16);
            int z = 14 + random.nextInt(4);
            ChunkPos pos = new ChunkPos(x >> 4, z >> 4);
            played.put(pos, set(played.get(pos), x, 64 + random.nextInt(2), z, STATES[random.nextInt(STATES.length)]));
        }
        return played;
    }

    @Property(tries = 300)
    void mergingChunkByChunkGivesWhatMergingAllAtOnceGives(@ForAll long seed, @ForAll @IntRange(min = 0, max = 60) int built,
                                                           @ForAll @IntRange(min = 0, max = 160) int editsOurs,
                                                           @ForAll @IntRange(min = 0, max = 160) int editsTheirs,
                                                           @ForAll boolean oursNewer) {
        Random random = new Random(seed);
        Map<ChunkPos, ChunkData> empty = new HashMap<>();
        for (int x = 0; x < WIDTH; x++) {
            for (int z = 0; z < DEPTH; z++) {
                empty.put(new ChunkPos(x, z), TestChunks.empty(x, z));
            }
        }
        Map<ChunkPos, ChunkData> base = play(empty, random, built);
        Map<ChunkPos, ChunkData> ours = play(base, random, editsOurs);
        Map<ChunkPos, ChunkData> theirs = play(base, random, editsTheirs);
        MergeContext context = context(oursNewer ? Side.OURS : Side.THEIRS);

        DimensionMerger.Result whole = DimensionMerger.merge(base, ours, theirs, Map.of(), context, DimensionMerger.Relight.CHANGED);
        Statistics.collect(whole.unitRepairs() == 0 ? "no unit repaired" : whole.unitRepairs() < 5 ? "a few" : "many");
        for (ChunkPos pos : base.keySet()) {
            SortedSet<ChunkPos> around = new TreeSet<>();
            for (ChunkPos other : base.keySet()) {
                boolean near = Math.abs(other.x() - pos.x()) <= DimensionMerger.HALO && Math.abs(other.z() - pos.z()) <= DimensionMerger.HALO;
                // A chunk both sides changed alike counts too: it holds changes of both. Two sides that put
                // the same half of a chest in the same place have one more unit to repair there.
                boolean changed = !ours.get(other).equals(theirs.get(other)) || !ours.get(other).equals(base.get(other));
                if (near && !other.equals(pos) && changed) {
                    around.add(other);
                }
            }
            DimensionMerger.Result piece = DimensionMerger.merge(new DimensionMerger.Window() {
                @Override
                public SortedSet<ChunkPos> wanted() {
                    return new TreeSet<>(List.of(pos));
                }

                @Override
                public SortedSet<ChunkPos> around() {
                    return around;
                }

                @Override
                public DimensionMerger.Inputs load(ChunkPos at) {
                    return base.containsKey(at) ? new DimensionMerger.Inputs(base.get(at), ours.get(at), theirs.get(at), null) : null;
                }
            }, context, DimensionMerger.Relight.CHANGED);

            assertEquals(whole.chunks().get(pos), piece.chunks().get(pos), "chunk " + pos);
            List<ReportEntry> expected = whole.entries().stream().filter(entry -> entry.pos().chunk().equals(pos)).toList();
            assertEquals(expected, piece.entries(), "report of chunk " + pos);
            assertEquals(whole.sources().get(pos), piece.sources().get(pos));
            assertEquals(whole.relit().contains(pos), piece.relit().contains(pos));
        }
    }
}
