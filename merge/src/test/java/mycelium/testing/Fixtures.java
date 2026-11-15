package mycelium.testing;

import mycelium.json.Json;
import mycelium.nbt.CompoundTag;
import mycelium.region.RegionFile;
import mycelium.region.RegionSlot;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The probe worlds under src/test/resources/fixtures: real Minecraft 26.3 worlds built by
 * tools/mc/build_probe_world.py. See docs/FORMAT-26.3.md.
 */
public final class Fixtures {

    /** Fixture worlds, named after the region-file-compression they were written with. */
    public static final List<String> WORLDS = List.of("probe-zlib", "probe-lz4", "probe-none");

    /** The kinds of region storage in a dimension folder. */
    public static final List<String> STORAGE = List.of("region", "entities", "poi");

    private static final String OVERWORLD = "world/dimensions/minecraft/overworld";

    private Fixtures() {
    }

    public static Path root() {
        String path = System.getProperty("mycelium.fixtures");
        if (path == null) {
            throw new IllegalStateException("system property mycelium.fixtures is not set; run tests through Gradle");
        }
        return Path.of(path);
    }

    public static Path world(String name) {
        return root().resolve(name).resolve("world");
    }

    /**
     * One of the three worlds of the play session: {@code base}, {@code ours} or {@code theirs}.
     * They were played by hand in the 26.3 client and cut down by tools/mc/install_play_fixture.py
     * to the player files, the maps, the entity region with the boats and the two chunks with the
     * barrels. See docs/SCENARIOS.md.
     */
    public static Path playSession(String side) {
        return root().resolve("play-session").resolve(side);
    }

    /** One chunk of a fixture region file. */
    public record FixtureChunk(String world, String storage, RegionFile region, int slot, RegionSlot.Chunk chunk) {

        public CompoundTag root() {
            return chunk.root();
        }

        @Override
        public String toString() {
            return world + "/" + storage + "/" + region.pos().fileName() + " slot " + slot;
        }
    }

    public static List<RegionFile> regions(String world, String storage) {
        Path directory = root().resolve(world).resolve(OVERWORLD).resolve(storage);
        try (Stream<Path> files = Files.list(directory)) {
            List<RegionFile> regions = new ArrayList<>();
            for (Path file : files.filter(f -> f.getFileName().toString().endsWith(".mca")).sorted().toList()) {
                regions.add(RegionFile.read(file));
            }
            return regions;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Every chunk of one storage kind in one fixture world. Fails if any slot is corrupt. */
    public static List<FixtureChunk> chunks(String world, String storage) {
        List<FixtureChunk> chunks = new ArrayList<>();
        for (RegionFile region : regions(world, storage)) {
            for (int slot : region.usedSlots()) {
                if (!(region.slot(slot) instanceof RegionSlot.Chunk chunk)) {
                    throw new IllegalStateException(region.file() + " slot " + slot + ": " + region.slot(slot));
                }
                chunks.add(new FixtureChunk(world, storage, region, slot, chunk));
            }
        }
        return chunks;
    }

    /** Every chunk of one storage kind across all fixture worlds. */
    public static List<FixtureChunk> allChunks(String storage) {
        List<FixtureChunk> chunks = new ArrayList<>();
        for (String world : WORLDS) {
            chunks.addAll(chunks(world, storage));
        }
        return chunks;
    }

    /** The chunk root at chunk coordinates in a fixture world's chunk storage. */
    public static CompoundTag chunkRoot(String world, int chunkX, int chunkZ) {
        for (RegionFile region : regions(world, "region")) {
            if (region.pos().x() == chunkX >> 5 && region.pos().z() == chunkZ >> 5
                    && region.slot(chunkX, chunkZ) instanceof RegionSlot.Chunk chunk) {
                return chunk.root();
            }
        }
        throw new IllegalArgumentException("no chunk " + chunkX + "," + chunkZ + " in " + world);
    }

    /** The parsed expected.json of a fixture world: what the build script placed. */
    public static Map<String, Object> expected(String world) {
        try {
            return Json.object(Json.parse(Files.readString(root().resolve(world).resolve("expected.json"))), "expected.json");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
