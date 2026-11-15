package mycelium.merge;

/** One of the two divergent copies being merged. */
public enum Side {
    /** World A, {@code --ours}. */
    OURS,
    /** World B, {@code --theirs}. */
    THEIRS;

    public Side other() {
        return this == OURS ? THEIRS : OURS;
    }

    /** The name used in the report and on the command line. */
    public String label() {
        return this == OURS ? "ours" : "theirs";
    }
}
