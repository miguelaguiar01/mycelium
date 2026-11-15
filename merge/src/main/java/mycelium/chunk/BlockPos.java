package mycelium.chunk;

/** An absolute block position. Ordered by y, then z, then x, the order blocks have inside a section. */
public record BlockPos(int x, int y, int z) implements Comparable<BlockPos> {

    public ChunkPos chunk() {
        return new ChunkPos(x >> 4, z >> 4);
    }

    @Override
    public int compareTo(BlockPos other) {
        int byY = Integer.compare(y, other.y);
        if (byY != 0) {
            return byY;
        }
        int byZ = Integer.compare(z, other.z);
        return byZ != 0 ? byZ : Integer.compare(x, other.x);
    }
}
