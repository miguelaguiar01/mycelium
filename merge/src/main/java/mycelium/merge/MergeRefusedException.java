package mycelium.merge;

/** The inputs cannot be merged, for example because they disagree on where a chunk is. */
public class MergeRefusedException extends Exception {

    private static final long serialVersionUID = 1L;

    public MergeRefusedException(String message) {
        super(message);
    }
}
