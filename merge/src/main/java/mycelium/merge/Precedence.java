package mycelium.merge;

import mycelium.chunk.ChunkPos;

/**
 * Answers which side is newer for a change. In this milestone the answer is the {@code --newer}
 * side everywhere. Later it is backed by the hybrid logical clocks of the session log entries,
 * which are per chunk.
 */
@FunctionalInterface
public interface Precedence {

    Side newerIn(ChunkPos chunk);

    static Precedence always(Side side) {
        return chunk -> side;
    }
}
