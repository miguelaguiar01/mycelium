package mycelium.report;

public enum Severity {
    /** Both sides changed the same thing in different ways, and one had to lose. */
    CONFLICT,
    /** A rule settled it without a choice between real edits. */
    RESOLVED,
    /** The inputs could not be merged here. */
    REFUSED
}
