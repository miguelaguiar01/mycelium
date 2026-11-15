package mycelium.chunk;

/**
 * A chunk's NBT does not have the layout this codec was verified against. The chunk must be
 * refused and reported, not guessed at.
 */
public class ChunkFormatException extends Exception {

    private static final long serialVersionUID = 1L;

    public ChunkFormatException(String message) {
        super(message);
    }
}
