package mycelium.testing;

import mycelium.chunk.ChunkCodec;
import mycelium.chunk.ChunkFormatException;
import mycelium.chunk.Entities;
import mycelium.chunk.EntityChunk;
import mycelium.chunk.EntityChunkCodec;
import mycelium.nbt.CompoundTag;
import mycelium.nbt.NbtIo;
import mycelium.region.Compression;
import mycelium.region.RegionFile;
import mycelium.region.RegionPos;
import mycelium.region.RegionSlot;
import mycelium.region.RegionWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Passes a whole world through the format layer and writes the result as a new world, so the
 * game can be asked whether it still loads. Used by tools/mc/check_rewritten_world.py.
 *
 * <p>Chunks go through the chunk codec, entity chunks through the entity codec and rider
 * flattening, POI chunks and standalone data files through the NBT layer. Everything is written
 * with sorted compound keys and the requested compression.
 *
 * <pre>./gradlew :merge:rewriteWorld --args="&lt;world&gt; &lt;new world&gt; ZLIB|LZ4|NONE"</pre>
 */
public final class RewriteWorld {

    private RewriteWorld() {
    }

    public static void main(String[] args) throws IOException, ChunkFormatException {
        if (args.length != 3) {
            System.err.println("usage: RewriteWorld <world> <new world> ZLIB|LZ4|NONE");
            System.exit(2);
        }
        Path source = Path.of(args[0]);
        Path target = Path.of(args[1]);
        Compression compression = Compression.valueOf(args[2]);
        if (Files.exists(target)) {
            throw new IOException(target + " already exists");
        }
        List<Path> files;
        try (Stream<Path> walk = Files.walk(source)) {
            files = walk.filter(Files::isRegularFile).sorted().toList();
        }
        int chunks = 0;
        int entityChunks = 0;
        int poiChunks = 0;
        int dataFiles = 0;
        for (Path file : files) {
            Path relative = source.relativize(file);
            Path out = target.resolve(relative);
            String name = file.getFileName().toString();
            String storage = file.getParent().getFileName().toString();
            Files.createDirectories(out.getParent());
            if (name.equals("session.lock") || name.endsWith(".mcc")) {
                continue;   // the lock is never copied; external chunks are rewritten with their region
            }
            if (RegionPos.parse(name) != null) {
                RegionFile region = RegionFile.read(file);
                SortedMap<Integer, RegionWriter.Entry> rewritten = new TreeMap<>();
                for (int slot : region.usedSlots()) {
                    if (!(region.slot(slot) instanceof RegionSlot.Chunk chunk)) {
                        throw new IOException(file + " slot " + slot + ": " + region.slot(slot));
                    }
                    CompoundTag root = switch (storage) {
                        case "region" -> ChunkCodec.encode(ChunkCodec.decode(chunk.root()));
                        case "entities" -> {
                            EntityChunk decoded = EntityChunkCodec.decode(chunk.root());
                            yield EntityChunkCodec.encode(new EntityChunk(decoded.dataVersion(), decoded.pos(),
                                    Entities.nest(Entities.flatten(decoded.entities())), decoded.extra()));
                        }
                        default -> chunk.root();
                    };
                    switch (storage) {
                        case "region" -> chunks++;
                        case "entities" -> entityChunks++;
                        default -> poiChunks++;
                    }
                    rewritten.put(slot, new RegionWriter.Entry(chunk.timestamp(), root));
                }
                RegionWriter.write(out.getParent(), region.pos(), rewritten, compression, NbtIo.KeyOrder.SORTED);
            } else if (name.endsWith(".dat")) {
                NbtIo.writeGzipFile(out, NbtIo.readGzipFile(file), NbtIo.KeyOrder.SORTED);
                dataFiles++;
            } else {
                Files.copy(file, out);
            }
        }
        System.out.println("rewrote " + chunks + " chunks, " + entityChunks + " entity chunks, " + poiChunks
                + " POI chunks and " + dataFiles + " data files as " + compression + " into " + target);
    }
}
