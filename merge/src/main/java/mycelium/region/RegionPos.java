package mycelium.region;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The coordinates of a region file, each covering 32 by 32 chunks. */
public record RegionPos(int x, int z) implements Comparable<RegionPos> {

    private static final Pattern FILE_NAME = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");

    /** Chunks along one side of a region. */
    public static final int SIZE = 32;

    /** Slots in a region file. */
    public static final int SLOTS = SIZE * SIZE;

    /** The world ends at 30 million blocks, which is region 58,594. */
    private static final int MAX_COORDINATE = 60_000;

    /** Parses {@code r.X.Z.mca}; returns null for any other name. */
    public static RegionPos parse(String fileName) {
        Matcher matcher = FILE_NAME.matcher(fileName);
        if (!matcher.matches()) {
            return null;
        }
        try {
            RegionPos pos = new RegionPos(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)));
            // Only the name the game itself writes, so that the file is found again under it, and only
            // coordinates whose chunks fit in an int.
            boolean inRange = Math.abs((long) pos.x) <= MAX_COORDINATE && Math.abs((long) pos.z) <= MAX_COORDINATE;
            return inRange && pos.fileName().equals(fileName) ? pos : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static RegionPos ofChunk(int chunkX, int chunkZ) {
        return new RegionPos(chunkX >> 5, chunkZ >> 5);
    }

    public String fileName() {
        return "r." + x + "." + z + ".mca";
    }

    /** The slot of a chunk inside its region file: {@code (z & 31) * 32 + (x & 31)}. */
    public static int slotIndex(int chunkX, int chunkZ) {
        return (chunkZ & 31) * SIZE + (chunkX & 31);
    }

    /** The world chunk X of a slot in this region. */
    public int chunkX(int slotIndex) {
        return x * SIZE + (slotIndex & 31);
    }

    /** The world chunk Z of a slot in this region. */
    public int chunkZ(int slotIndex) {
        return z * SIZE + (slotIndex >> 5);
    }

    /** The name of the external file that holds an oversized chunk of this region. */
    public String externalFileName(int slotIndex) {
        return "c." + chunkX(slotIndex) + "." + chunkZ(slotIndex) + ".mcc";
    }

    @Override
    public int compareTo(RegionPos other) {
        int byX = Integer.compare(x, other.x);
        return byX != 0 ? byX : Integer.compare(z, other.z);
    }
}
