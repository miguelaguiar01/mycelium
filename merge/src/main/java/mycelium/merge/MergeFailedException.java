package mycelium.merge;

import java.io.IOException;

/**
 * The merge stopped after it had begun to write, so the output folder is incomplete. A merge
 * that is refused, by contrast, has written nothing.
 */
public class MergeFailedException extends IOException {

    private static final long serialVersionUID = 1L;

    public MergeFailedException(IOException cause) {
        super(cause.toString(), cause);
    }
}
