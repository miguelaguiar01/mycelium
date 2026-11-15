package mycelium.merge;

/** Where the value of a merged cell came from. */
public enum Origin {
    /** Neither side changed it. */
    BASE,
    /** Only world A changed it, or a rule picked A's value. */
    OURS,
    /** Only world B changed it, or a rule picked B's value. */
    THEIRS,
    /** Both sides hold the same value, which differs from the base or has no base. */
    BOTH;

    public static Origin of(Side side) {
        return side == Side.OURS ? OURS : THEIRS;
    }
}
