package mycelium.chunk;

/** The world data versions this format layer was verified against. See docs/FORMAT-26.3.md. */
public final class DataVersions {

    /** Minecraft Java 26.3. */
    public static final int MC_26_3 = 5023;

    private DataVersions() {
    }

    /**
     * Refuses data from any other version. Layouts change between versions, and the game's own
     * upgrader must run before this layer reads a world.
     */
    public static void require(int dataVersion, String what) throws ChunkFormatException {
        if (dataVersion != MC_26_3) {
            throw new ChunkFormatException(
                    what + " has data version " + dataVersion + "; only " + MC_26_3 + " (Minecraft 26.3) is supported");
        }
    }
}
