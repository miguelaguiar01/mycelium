package mycelium.chunk;

/** The coordinates of a chunk column. Ordered by z, then x, the order of slots in a region file. */
public record ChunkPos(int x, int z) implements Comparable<ChunkPos> {

    @Override
    public int compareTo(ChunkPos other) {
        int byZ = Integer.compare(z, other.z);
        return byZ != 0 ? byZ : Integer.compare(x, other.x);
    }
}
