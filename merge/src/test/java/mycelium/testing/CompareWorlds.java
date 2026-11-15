package mycelium.testing;

import mycelium.chunk.ChunkCodec;
import mycelium.chunk.ChunkData;
import mycelium.chunk.ChunkFormatException;
import mycelium.chunk.Entities;
import mycelium.chunk.EntityChunkCodec;
import mycelium.chunk.FlatEntity;
import mycelium.json.Json;
import mycelium.nbt.NbtIo;
import mycelium.region.RegionFile;
import mycelium.region.RegionPos;
import mycelium.region.RegionSlot;
import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Compares two worlds by what they decode to, not by their bytes: chunks as {@link ChunkData},
 * entity chunks as sets of entities, POI chunks and data files as NBT, player statistics and
 * advancements as JSON, anything else byte for byte. Two merges of the same worlds must agree
 * this way however the work was divided.
 *
 * <p>The order of entities and block entities inside a chunk is not compared: a chunk that is
 * copied keeps the game's order and one that is written again gets the canonical one. For
 * comparing two runs of the same merge to the byte, there is tools/mc/world_digest.py.
 */
public final class CompareWorlds {

    private CompareWorlds() {
    }

    /** @return a line for each slot or file that differs; empty if the worlds hold the same data */
    public static List<String> differences(Path first, Path second) throws IOException {
        List<String> differences = new ArrayList<>();
        differences.addAll(otherFiles(first, second));
        TreeSet<String> files = new TreeSet<>();
        for (Path world : new Path[] {first, second}) {
            Path dimensions = world.resolve("dimensions");
            if (Files.isDirectory(dimensions)) {
                try (Stream<Path> walk = Files.walk(dimensions)) {
                    walk.filter(file -> RegionPos.parse(file.getFileName().toString()) != null)
                            .forEach(file -> files.add(world.relativize(file).toString().replace('\\', '/')));
                }
            }
        }
        for (String file : files) {
            if (!Files.exists(first.resolve(file)) || !Files.exists(second.resolve(file))) {
                differences.add(file + " is only in " + (Files.exists(first.resolve(file)) ? first : second));
                continue;
            }
            String storage = Path.of(file).getParent().getFileName().toString();
            try (RegionFile a = RegionFile.open(first.resolve(file)); RegionFile b = RegionFile.open(second.resolve(file))) {
                for (int slot = 0; slot < RegionPos.SLOTS; slot++) {
                    String difference = difference(storage, a.slot(slot), b.slot(slot));
                    if (difference != null) {
                        differences.add(file + " chunk " + a.pos().chunkX(slot) + "," + a.pos().chunkZ(slot) + ": " + difference);
                    }
                }
            }
        }
        return differences;
    }

    /** The files outside region storage that are in one world only or decode differently. */
    private static List<String> otherFiles(Path first, Path second) throws IOException {
        List<String> differences = new ArrayList<>();
        TreeSet<String> files = new TreeSet<>();
        for (Path world : new Path[] {first, second}) {
            try (Stream<Path> walk = Files.walk(world)) {
                walk.filter(Files::isRegularFile).filter(file -> RegionPos.parse(file.getFileName().toString()) == null)
                        .filter(file -> !file.getFileName().toString().endsWith(".mcc"))
                        .forEach(file -> files.add(world.relativize(file).toString().replace('\\', '/')));
            }
        }
        for (String file : files) {
            if (file.equals("session.lock")) {
                continue;
            }
            if (!Files.exists(first.resolve(file)) || !Files.exists(second.resolve(file))) {
                differences.add(file + " is only in " + (Files.exists(first.resolve(file)) ? first : second));
            } else if (!decoded(first.resolve(file)).equals(decoded(second.resolve(file)))) {
                differences.add(file + " differs");
            }
        }
        return differences;
    }

    /** A file as something to compare: gzip NBT as its tag, JSON as its value, anything else as its bytes. */
    private static Object decoded(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length > 2 && bytes[0] == 0x1f && bytes[1] == (byte) 0x8b) {
            try {
                return NbtIo.readGzipFile(file);
            } catch (IOException e) {
                // Not NBT after all: compared as bytes.
            }
        } else if (file.getFileName().toString().endsWith(".json")) {
            try {
                return Json.parse(new String(bytes, StandardCharsets.UTF_8));
            } catch (IllegalArgumentException e) {
                // Not JSON after all: compared as bytes.
            }
        }
        return ByteBuffer.wrap(bytes);
    }

    private static String difference(String storage, RegionSlot a, RegionSlot b) {
        if (!(a instanceof RegionSlot.Chunk first) || !(b instanceof RegionSlot.Chunk second)) {
            return a.getClass() == b.getClass() && !(a instanceof RegionSlot.Chunk) ? null
                    : a.getClass().getSimpleName() + " and " + b.getClass().getSimpleName();
        }
        if (first.timestamp() != second.timestamp()) {
            return "timestamps " + first.timestamp() + " and " + second.timestamp();
        }
        try {
            if (storage.equals("region")) {
                ChunkData x = ChunkCodec.decode(first.root());
                ChunkData y = ChunkCodec.decode(second.root());
                if (x.equals(y)) {
                    return null;
                }
                List<String> fields = new ArrayList<>();
                for (RecordComponent component : ChunkData.class.getRecordComponents()) {
                    if (!Objects.equals(component.getAccessor().invoke(x), component.getAccessor().invoke(y))) {
                        fields.add(component.getName());
                    }
                }
                return "differs in " + fields;
            }
            if (storage.equals("entities")) {
                List<FlatEntity> x = Entities.flatten(EntityChunkCodec.decode(first.root()).entities());
                List<FlatEntity> y = Entities.flatten(EntityChunkCodec.decode(second.root()).entities());
                return new HashSet<>(x).equals(new HashSet<>(y)) ? null : "entities differ";
            }
            return first.root().equals(second.root()) ? null : "NBT differs";
        } catch (ChunkFormatException | ReflectiveOperationException e) {
            return "cannot be decoded: " + e.getMessage();
        }
    }
}
