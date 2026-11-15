package mycelium.nbt;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import mycelium.region.RegionFile;
import mycelium.testing.Fixtures;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Real 26.3 NBT must come back byte for byte: nothing dropped, reordered or retyped. */
class NbtFixtureRoundTripTest {

    static List<String> worlds() {
        return Fixtures.WORLDS;
    }

    @ParameterizedTest
    @MethodSource("worlds")
    void everyChunkOfEveryRegionFileRoundTripsByteForByte(String world) throws IOException {
        int chunks = 0;
        for (String storage : Fixtures.STORAGE) {
            for (RegionFile region : Fixtures.regions(world, storage)) {
                for (int slot : region.usedSlots()) {
                    byte[] original = region.rawNbt(slot);
                    assertNotNull(original, region.file() + " slot " + slot);
                    NamedTag root = NbtIo.read(original);
                    assertArrayEquals(original, NbtIo.write(root), region.file() + " slot " + slot);
                    assertEquals(root, NbtIo.read(NbtIo.write(root, NbtIo.KeyOrder.SORTED)),
                            "sorting keys changes bytes, not the value");
                    chunks++;
                }
            }
        }
        assertTrue(chunks > 300, "fixture " + world + " has chunks");
    }

    @ParameterizedTest
    @MethodSource("worlds")
    void everyStandaloneDataFileRoundTripsByteForByte(String world) throws IOException {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(Fixtures.world(world))) {
            files = walk.filter(file -> file.getFileName().toString().endsWith(".dat")).sorted().toList();
        }
        assertTrue(files.size() >= 10, "fixture " + world + " has data files");
        for (Path file : files) {
            byte[] original;
            try (InputStream in = new GZIPInputStream(Files.newInputStream(file))) {
                original = in.readAllBytes();
            }
            NamedTag root = NbtIo.read(original);
            assertArrayEquals(original, NbtIo.write(root), file.toString());
            assertEquals(root, NbtIo.readGzipFile(file));
        }
    }
}
