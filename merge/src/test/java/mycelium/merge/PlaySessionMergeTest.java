package mycelium.merge;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import mycelium.chunk.BlockPos;
import mycelium.chunk.ChunkCodec;
import mycelium.chunk.Entities;
import mycelium.chunk.EntityChunk;
import mycelium.chunk.EntityChunkCodec;
import mycelium.chunk.FlatEntity;
import mycelium.cli.Main;
import mycelium.json.Json;
import mycelium.nbt.CompoundTag;
import mycelium.nbt.DoubleTag;
import mycelium.nbt.IntTag;
import mycelium.nbt.ListTag;
import mycelium.nbt.NamedTag;
import mycelium.nbt.NbtIo;
import mycelium.nbt.StringTag;
import mycelium.nbt.Tag;
import mycelium.region.Compression;
import mycelium.region.RegionFile;
import mycelium.region.RegionPos;
import mycelium.region.RegionSlot;
import mycelium.region.RegionWriter;
import mycelium.testing.CompareWorlds;
import mycelium.testing.Fixtures;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Merges the worlds of the play session: one player, played by hand in the 26.3 client, once as
 * the base and then separately on two copies. See docs/SCENARIOS.md for what was done in each.
 *
 * <p>In short: both sides made a new map and put it in a barrel, ours at x=300 and theirs at
 * x=-300. Ours rowed the birch boat to the end of its channel and left the game sitting in it.
 * Theirs rowed the oak boat to the end of its channel and got out.
 */
class PlaySessionMergeTest {

    private static final String DIM = "dimensions/minecraft/overworld/";
    // Not a real account: the fixture is cut with the player's UUID replaced, see tools/mc/install_play_fixture.py.
    private static final String PLAYER = "00000000-0000-4000-8000-000000000001";
    private static final String PLAYER_FILE = "players/data/" + PLAYER + ".dat";
    private static final Path BASE = Fixtures.playSession("base");
    private static final Path OURS = Fixtures.playSession("ours");
    private static final Path THEIRS = Fixtures.playSession("theirs");
    private static final BlockPos OURS_BARREL = new BlockPos(300, -60, 4);
    private static final BlockPos THEIRS_BARREL = new BlockPos(-300, -60, 3);

    @TempDir
    Path temp;

    private record Run(int exit, String out, Path world, List<Map<String, Object>> entries) {

        List<String> details(String category) {
            return entries.stream().filter(entry -> category.equals(entry.get("category")))
                    .map(entry -> String.valueOf(entry.get("detail"))).toList();
        }
    }

    private Run merge(Path ours, Path theirs, String newer) throws IOException {
        Path out = Files.createTempDirectory(temp, "out");
        Path report = out.resolveSibling(out.getFileName() + ".json");
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit = Main.run(new String[] {"--base", BASE.toString(), "--ours", ours.toString(), "--theirs", theirs.toString(),
                "--newer", newer, "--out", out.toString(), "--report", report.toString()},
                new PrintStream(stdout), new PrintStream(stderr));
        assertTrue(exit == 0 || exit == 1, stdout + " " + stderr);
        List<Map<String, Object>> entries = new ArrayList<>();
        for (Object entry : Json.array(Json.object(Json.parse(Files.readString(report)), "report").get("entries"), "entries")) {
            entries.add(Json.object(entry, "entry"));
        }
        return new Run(exit, stdout.toString(), out, entries);
    }

    private Path copy(Path world, String name) throws IOException {
        Path target = temp.resolve(name);
        try (Stream<Path> walk = Files.walk(world)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                Path copy = target.resolve(world.relativize(file));
                Files.createDirectories(copy.getParent());
                Files.copy(file, copy);
            }
        }
        return target;
    }

    private static CompoundTag nbt(Path world, String file) throws IOException {
        return (CompoundTag) NbtIo.readGzipFile(world.resolve(file)).tag();
    }

    private static CompoundTag compound(CompoundTag from, String... path) {
        CompoundTag current = from;
        for (String key : path) {
            current = (CompoundTag) current.get(key);
        }
        return current;
    }

    /** The top-level entities of the world's entity region with the given id. */
    private static List<CompoundTag> entities(Path world, String id) throws Exception {
        List<CompoundTag> found = new ArrayList<>();
        RegionFile region = RegionFile.read(world.resolve(DIM + "entities/r.0.0.mca"));
        for (int slot : region.usedSlots()) {
            for (CompoundTag entity : EntityChunkCodec.decode(((RegionSlot.Chunk) region.slot(slot)).root()).entities()) {
                if (new StringTag(id).equals(entity.get("id"))) {
                    found.add(entity);
                }
            }
        }
        return found;
    }

    private static void assertNoEntityIsStoredTwice(Path world) throws Exception {
        List<CompoundTag> all = new ArrayList<>();
        RegionFile region = RegionFile.read(world.resolve(DIM + "entities/r.0.0.mca"));
        for (int slot : region.usedSlots()) {
            all.addAll(EntityChunkCodec.decode(((RegionSlot.Chunk) region.slot(slot)).root()).entities());
        }
        List<FlatEntity> flat = Entities.flatten(all);
        assertEquals(flat.size(), new HashSet<>(flat.stream().map(FlatEntity::uuid).toList()).size());
    }

    /** The id of the map in the barrel at a position. */
    private static int mapIn(Path world, BlockPos barrel) throws Exception {
        int x = barrel.x() >> 4;
        int z = barrel.z() >> 4;
        RegionFile region = RegionFile.read(world.resolve(DIM + "region/r." + (x >> 5) + "." + (z >> 5) + ".mca"));
        CompoundTag blockEntity = ChunkCodec.decode(((RegionSlot.Chunk) region.slot(x, z)).root()).blockEntities().get(barrel);
        CompoundTag item = (CompoundTag) ((ListTag) blockEntity.get("Items")).get(0);
        assertEquals(new StringTag("minecraft:filled_map"), item.get("id"));
        return ((IntTag) compound(item, "components").get("minecraft:map_id")).value();
    }

    private static int mapCentre(Path world, int id) throws IOException {
        return ((IntTag) compound(nbt(world, "data/minecraft/maps/" + id + ".dat"), "data").get("xCenter")).value();
    }

    private static Map<String, Object> stats(Path world, String group) throws IOException {
        Object root = Json.parse(Files.readString(world.resolve("players/stats/" + PLAYER + ".json")));
        return Json.object(Json.object(Json.object(root, "stats file").get("stats"), "stats").get("minecraft:" + group), group);
    }

    private static double x(CompoundTag entity) {
        return ((DoubleTag) ((ListTag) entity.get("Pos")).get(0)).value();
    }

    /** Moves the birch boat of a world within its chunk, as a player who rowed it a little would. */
    private static void rowBirchBoatTo(Path world, double x) throws Exception {
        editEntities(world, "minecraft:birch_boat", entity -> {
            List<Tag> pos = ((ListTag) entity.get("Pos")).elements();
            return entity.with("Pos", ListTag.of(List.of(new DoubleTag(x), pos.get(1), pos.get(2))));
        });
    }

    /** Rewrites the entities with an id in a world's entity region; an entity mapped to null is taken out. */
    private static void editEntities(Path world, String id, UnaryOperator<CompoundTag> change) throws Exception {
        Path folder = world.resolve(DIM + "entities");
        RegionFile region = RegionFile.read(folder.resolve("r.0.0.mca"));
        SortedMap<Integer, RegionWriter.Entry> chunks = new TreeMap<>();
        for (int slot : region.usedSlots()) {
            RegionSlot.Chunk read = (RegionSlot.Chunk) region.slot(slot);
            EntityChunk chunk = EntityChunkCodec.decode(read.root());
            List<CompoundTag> entities = new ArrayList<>();
            for (CompoundTag entity : chunk.entities()) {
                CompoundTag changed = new StringTag(id).equals(entity.get("id")) ? change.apply(entity) : entity;
                if (changed != null) {
                    entities.add(changed);
                }
            }
            chunks.put(slot, new RegionWriter.Entry(read.timestamp(), EntityChunkCodec.encode(
                    new EntityChunk(chunk.dataVersion(), chunk.pos(), entities, chunk.extra()))));
        }
        RegionWriter.write(folder, region.pos(), chunks, Compression.ZLIB, NbtIo.KeyOrder.STORED);
    }

    /** Damages the entity chunk at a chunk position of a world: its slot then claims a length of zero. */
    private static void damageEntityChunk(Path world, int chunkX, int chunkZ) throws IOException {
        Path file = world.resolve(DIM + "entities/r.0.0.mca");
        byte[] bytes = Files.readAllBytes(file);
        int sector = ByteBuffer.wrap(bytes).getInt(RegionPos.slotIndex(chunkX, chunkZ) * 4) >>> 8;
        assertTrue(sector >= 2, "the chunk is there to be damaged");
        ByteBuffer.wrap(bytes).putInt(sector * 4096, 0);
        Files.write(file, bytes);
    }

    /** A copy of theirs in which the player never played: the player files are the base's. */
    private Path theirsWithoutThePlayer(String name) throws IOException {
        Path world = copy(THEIRS, name);
        try (Stream<Path> walk = Files.walk(world.resolve("players"))) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                Files.delete(file);
            }
        }
        try (Stream<Path> walk = Files.walk(BASE.resolve("players"))) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                Files.copy(file, world.resolve(BASE.relativize(file)));
            }
        }
        return world;
    }

    @Test
    void theFixtureIsWhatThePlaySessionLeft() throws Exception {
        assertEquals(new StringTag("minecraft:birch_boat"),
                compound(nbt(OURS, PLAYER_FILE), "RootVehicle", "Entity").get("id"), "ours left the game in the boat");
        assertTrue(entities(OURS, "minecraft:birch_boat").isEmpty(), "so it is in the player's file, not in the world");
        assertEquals(1, entities(THEIRS, "minecraft:birch_boat").size());
        assertEquals(1, mapIn(OURS, OURS_BARREL));
        assertEquals(1, mapIn(THEIRS, THEIRS_BARREL), "both sides numbered their new map 1");
        assertEquals(256, mapCentre(OURS, 1));
        assertEquals(-256, mapCentre(THEIRS, 1));
    }

    @Test
    void mergingAPlayedWorldWithItselfChangesNothing() throws Exception {
        Path out = temp.resolve("out");
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        assertEquals(0, Main.run(new String[] {"--base", OURS.toString(), "--ours", OURS.toString(), "--theirs",
                OURS.toString(), "--newer", "theirs", "--out", out.toString()},
                new PrintStream(stdout), new PrintStream(new ByteArrayOutputStream())));
        assertTrue(stdout.toString().contains("0 conflicts, 0 resolved, 0 refused"), stdout.toString());
        for (String file : List.of(PLAYER_FILE, PLAYER_FILE + "_old", "data/minecraft/maps/0.dat",
                "data/minecraft/maps/1.dat", "data/minecraft/maps/last_id.dat")) {
            assertArrayEquals(Files.readAllBytes(OURS.resolve(file)), Files.readAllBytes(out.resolve(file)), file);
        }
        assertTrue(entities(out, "minecraft:birch_boat").isEmpty(), "the boat stays in the player's file");
        assertEquals(1, mapIn(out, OURS_BARREL));
        for (String file : List.of("players/stats/" + PLAYER + ".json", "players/advancements/" + PLAYER + ".json")) {
            assertEquals(Json.parse(Files.readString(OURS.resolve(file))), Json.parse(Files.readString(out.resolve(file))), file);
        }
    }

    @Test
    void aSideThatChangedNothingLeavesTheOtherSidesWorld() throws Exception {
        // merge(O, A, O) is A and merge(O, O, B) is B, for the whole world: chunks, entities, player files, maps.
        for (String newer : List.of("ours", "theirs")) {
            Run oursOnly = merge(OURS, BASE, newer);
            assertEquals(0, oursOnly.exit(), oursOnly.out());
            assertEquals(List.of(), CompareWorlds.differences(OURS, oursOnly.world()));
            Run theirsOnly = merge(BASE, THEIRS, newer);
            assertEquals(List.of(), CompareWorlds.differences(THEIRS, theirsOnly.world()));
            assertTrue(oursOnly.entries().isEmpty() && theirsOnly.entries().isEmpty(), "and there is nothing to report");
        }
    }

    @Test
    void swappingTheSidesAndTheNewerFlagGivesTheSameWorld() throws Exception {
        Run one = merge(OURS, THEIRS, "theirs");
        Run other = merge(THEIRS, OURS, "ours");
        // Which world is called ours shows only in the name of the backup file.
        assertEquals(List.of(), CompareWorlds.differences(one.world(), other.world()).stream()
                .filter(line -> !line.contains("-backup-")).toList());
        assertEquals(one.entries().size(), other.entries().size());
    }

    @Test
    void theNewerSidesPlayerWinsAndTheOlderSidesWorkIsKept() throws Exception {
        Run run = merge(OURS, THEIRS, "theirs");
        assertEquals(1, run.exit());
        assertTrue(run.out().contains("1 conflicts"), run.out());
        assertFalse(run.out().contains("note:"), run.out());
        assertEquals(List.of(PLAYER_FILE), run.details("player"), "the game's backup of the file is not a second conflict");

        // The player file is theirs. Ours is kept next to it.
        CompoundTag player = nbt(run.world(), PLAYER_FILE);
        assertNull(player.get("RootVehicle"));
        assertEquals(compound(nbt(THEIRS, PLAYER_FILE)).get("Inventory"), player.get("Inventory"));
        assertArrayEquals(Files.readAllBytes(OURS.resolve(PLAYER_FILE)),
                Files.readAllBytes(run.world().resolve(PLAYER_FILE + ".ours-backup-1")));
        assertEquals(43, ((ListTag) compound(player, "recipeBook").get("recipes")).size(),
                "the recipes either side unlocked: their advancements are merged as done and would not unlock them again");

        // The boat ours left the game in is back in the world, where ours rowed it to.
        List<CompoundTag> birch = entities(run.world(), "minecraft:birch_boat");
        assertEquals(1, birch.size());
        assertEquals(63.3125, x(birch.get(0)));
        assertEquals(compound(nbt(OURS, PLAYER_FILE), "RootVehicle", "Entity"), birch.get(0));
        List<CompoundTag> oak = entities(run.world(), "minecraft:oak_boat");
        assertEquals(1, oak.size());
        assertEquals(63.3125, x(oak.get(0)), "theirs rowed the oak boat");
        assertNoEntityIsStoredTwice(run.world());

        // Both new maps exist. The older side's is renumbered, in its file and in the barrel.
        assertEquals(-256, mapCentre(run.world(), 1));
        assertEquals(256, mapCentre(run.world(), 2));
        assertEquals(1, mapIn(run.world(), THEIRS_BARREL));
        assertEquals(2, mapIn(run.world(), OURS_BARREL));
        assertEquals(new IntTag(2), compound(nbt(run.world(), "data/minecraft/maps/last_id.dat"), "data").get("map"));
        assertEquals(List.of("data/minecraft/maps/1.dat of ours is now data/minecraft/maps/2.dat"), run.details("file"));

        // Counters add up; a timer does not.
        Map<String, Object> custom = stats(run.world(), "custom");
        assertEquals(5800 + 5131 - 2730, custom.get("minecraft:play_time"));
        assertEquals(6161 + 6074, custom.get("minecraft:boat_one_cm"));
        assertEquals(5, custom.get("minecraft:drop"));
        assertEquals(5131, custom.get("minecraft:time_since_death"));
        assertEquals(3, stats(run.world(), "used").get("minecraft:map"));

        Object advancements = Json.parse(Files.readString(run.world().resolve("players/advancements/" + PLAYER + ".json")));
        Map<String, Object> diet = Json.object(Json.object(advancements, "advancements")
                .get("minecraft:husbandry/balanced_diet"), "advancement");
        assertEquals(List.of("apple", "bread"), List.copyOf(Json.object(diet.get("criteria"), "criteria").keySet()));
    }

    @Test
    void thePlayerStaysInTheBoatWhenThatSideIsNewer() throws Exception {
        Run run = merge(OURS, THEIRS, "ours");
        assertEquals(1, run.exit());
        CompoundTag player = nbt(run.world(), PLAYER_FILE);
        assertEquals(compound(nbt(OURS, PLAYER_FILE), "RootVehicle"), player.get("RootVehicle"));
        assertTrue(entities(run.world(), "minecraft:birch_boat").isEmpty());
        assertEquals(1, entities(run.world(), "minecraft:oak_boat").size());

        assertEquals(256, mapCentre(run.world(), 1));
        assertEquals(-256, mapCentre(run.world(), 2));
        assertEquals(1, mapIn(run.world(), OURS_BARREL));
        assertEquals(2, mapIn(run.world(), THEIRS_BARREL));
        assertEquals(5800, stats(run.world(), "custom").get("minecraft:time_since_death"));
        assertEquals(5800 + 5131 - 2730, stats(run.world(), "custom").get("minecraft:play_time"));
    }

    @Test
    void aPlayerOnlyOneSidePlayedKeepsFileAndBoat() throws Exception {
        // The usual case between friends: the player of ours never joined theirs.
        Run run = merge(OURS, theirsWithoutThePlayer("theirs"), "theirs");
        assertEquals(0, run.exit(), run.out());
        assertArrayEquals(Files.readAllBytes(OURS.resolve(PLAYER_FILE)), Files.readAllBytes(run.world().resolve(PLAYER_FILE)),
                "a file only one side changed is written as the game stored it");
        assertFalse(Files.exists(run.world().resolve(PLAYER_FILE + ".ours-backup-1")));
        assertTrue(entities(run.world(), "minecraft:birch_boat").isEmpty(), "the boat is in the player's file");
        assertEquals(5800, stats(run.world(), "custom").get("minecraft:play_time"));
    }

    @Test
    void aBoatOneSideLeftTheGameInAndTheOtherRowedAwayIsStoredOnce() throws Exception {
        Path theirs = theirsWithoutThePlayer("theirs");
        rowBirchBoatTo(theirs, 5.5);

        // Theirs is newer, so the boat is where theirs rowed it, and the player of ours is no longer in it.
        Run rowed = merge(OURS, theirs, "theirs");
        List<CompoundTag> birch = entities(rowed.world(), "minecraft:birch_boat");
        assertEquals(1, birch.size());
        assertEquals(5.5, x(birch.get(0)));
        CompoundTag player = nbt(rowed.world(), PLAYER_FILE);
        assertNull(player.get("RootVehicle"));
        assertEquals(nbt(OURS, PLAYER_FILE).without("RootVehicle"), player, "nothing else in the file changes");
        assertEquals(1, rowed.details("player").size());
        assertTrue(rowed.details("player").get(0).contains("RootVehicle"), rowed.details("player").toString());
        assertNoEntityIsStoredTwice(rowed.world());

        // Ours is newer, so the player still sits in the boat and the world has no second one.
        Run ridden = merge(OURS, theirs, "ours");
        assertTrue(entities(ridden.world(), "minecraft:birch_boat").isEmpty());
        assertEquals(compound(nbt(OURS, PLAYER_FILE), "RootVehicle"), nbt(ridden.world(), PLAYER_FILE).get("RootVehicle"));
    }

    @Test
    void anEntityChunkOneSideCannotReadDoesNotDeleteWhatTheOtherSideStillHas() throws Exception {
        // Ours is the base with the birch boat's chunk damaged. Theirs never touched that boat.
        Path ours = copy(BASE, "ours");
        damageEntityChunk(ours, 0, 1);
        Run run = merge(ours, THEIRS, "ours");
        assertEquals(1, entities(run.world(), "minecraft:birch_boat").size(),
                "an unreadable chunk is not an empty one: reading it as empty would remove the boat");
        assertEquals(63.3125, x(entities(run.world(), "minecraft:oak_boat").get(0)), "the rest still merges");
        assertEquals(1, run.exit());
        assertEquals(1, run.details("refused").size());
        assertTrue(run.details("refused").get(0).contains("declared length is 0")
                && run.details("refused").get(0).contains("ours counts as not having changed the entities there"),
                run.details("refused").toString());
    }

    @Test
    void anEntityWithoutAPositionCostsItsChunkNotTheDimension() throws Exception {
        Path ours = copy(BASE, "ours");
        editEntities(ours, "minecraft:birch_boat", boat -> boat.without("Pos"));
        Run run = merge(ours, THEIRS, "ours");
        assertEquals(1, entities(run.world(), "minecraft:birch_boat").size(), "the boat as the base and theirs have it");
        assertEquals(63.3125, x(entities(run.world(), "minecraft:oak_boat").get(0)), "every other entity is merged as usual");
        assertEquals(1, run.details("refused").size());
        assertTrue(run.details("refused").get(0).contains("Pos"), run.details("refused").toString());
    }

    @Test
    void aSecondMergeKeepsTheBackupOfTheFirst() throws Exception {
        // The inputs of a later merge come from a merged world, so they can hold a backup already.
        List<Path> worlds = List.of(copy(BASE, "base"), copy(OURS, "ours"), copy(THEIRS, "theirs"));
        for (Path world : worlds) {
            Files.writeString(world.resolve(PLAYER_FILE + ".ours-backup-1"), "the backup of an earlier merge");
        }
        Path out = temp.resolve("out");
        assertEquals(1, Main.run(new String[] {"--base", worlds.get(0).toString(), "--ours", worlds.get(1).toString(),
                "--theirs", worlds.get(2).toString(), "--newer", "theirs", "--out", out.toString()},
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(new ByteArrayOutputStream())));
        assertEquals("the backup of an earlier merge", Files.readString(out.resolve(PLAYER_FILE + ".ours-backup-1")));
        assertArrayEquals(Files.readAllBytes(OURS.resolve(PLAYER_FILE)),
                Files.readAllBytes(out.resolve(PLAYER_FILE + ".ours-backup-2")), "the new backup gets the next free number");
    }

    @Test
    void anEntityOnAPlayersShoulderIsKeptOnceLikeAVehicle() throws Exception {
        // The game stores a parrot on a shoulder inside the player's file. The oak boat stands in for the parrot:
        // ours takes it out of the world and onto the shoulder, while theirs moves it in the world.
        Path ours = copy(OURS, "ours");
        CompoundTag[] taken = new CompoundTag[1];
        editEntities(ours, "minecraft:oak_boat", boat -> {
            taken[0] = boat;
            return null;
        });
        NbtIo.writeGzipFile(ours.resolve(PLAYER_FILE), NamedTag.of(nbt(ours, PLAYER_FILE).with("ShoulderEntityLeft", taken[0])),
                NbtIo.KeyOrder.STORED);
        Path theirs = theirsWithoutThePlayer("theirs");

        Run moved = merge(ours, theirs, "theirs");
        assertEquals(1, entities(moved.world(), "minecraft:oak_boat").size(), "theirs is newer: it is where theirs moved it");
        assertEquals(63.3125, x(entities(moved.world(), "minecraft:oak_boat").get(0)));
        assertNull(nbt(moved.world(), PLAYER_FILE).get("ShoulderEntityLeft"), "and no longer on the shoulder as well");
        assertNoEntityIsStoredTwice(moved.world());

        Run carried = merge(ours, theirs, "ours");
        assertTrue(entities(carried.world(), "minecraft:oak_boat").isEmpty(), "ours is newer: it is on the shoulder");
        assertEquals(taken[0], nbt(carried.world(), PLAYER_FILE).get("ShoulderEntityLeft"));
    }

    @Test
    void aPlayerFileThatCannotBeReadDoesNotWin() throws Exception {
        Path ours = copy(OURS, "ours");
        byte[] bytes = Files.readAllBytes(ours.resolve(PLAYER_FILE));
        Files.write(ours.resolve(PLAYER_FILE), java.util.Arrays.copyOf(bytes, bytes.length / 2));
        // Ours is the newer side, but its file is cut short. It counts as unchanged, so theirs' is written.
        Run run = merge(ours, THEIRS, "ours");
        assertEquals(nbt(THEIRS, PLAYER_FILE), nbt(run.world(), PLAYER_FILE));
        assertEquals(1, run.exit());
        assertEquals(1, run.details("refused").size());
        assertTrue(run.details("refused").get(0).contains("of ours cannot be read"), run.details("refused").toString());
        assertTrue(run.details("player").isEmpty(), "there is no second version to conflict with");
    }

    @Test
    void aPlayerWithoutAFileInTheBaseHasTheCountsOfBothSidesAddedUp() throws Exception {
        Path base = copy(BASE, "base");
        Files.delete(base.resolve("players/stats/" + PLAYER + ".json"));
        Path out = temp.resolve("out");
        assertEquals(1, Main.run(new String[] {"--base", base.toString(), "--ours", OURS.toString(), "--theirs",
                THEIRS.toString(), "--newer", "theirs", "--out", out.toString()},
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(new ByteArrayOutputStream())));
        assertEquals(5800 + 5131, stats(out, "custom").get("minecraft:play_time"));
        Object root = Json.parse(Files.readString(out.resolve("players/stats/" + PLAYER + ".json")));
        assertEquals(5023, Json.object(root, "stats file").get("DataVersion"), "only the numbers under stats are counters");
    }

    @Test
    void mapIdsAreRenumberedInsideContainersInsideItems() throws Exception {
        MapIds maps = MapIds.read(BASE, OURS, THEIRS, Side.THEIRS);
        assertTrue(maps.renumbers(Side.OURS) && !maps.renumbers(Side.THEIRS));
        assertEquals(2, maps.last());
        // A chest holding a shulker box that holds the map.
        CompoundTag old = mapItem(0);
        CompoundTag chest = CompoundTag.of("Items", ListTag.of(List.of(old, CompoundTag.builder()
                .put("id", new StringTag("minecraft:shulker_box"))
                .put("components", CompoundTag.of("minecraft:container", ListTag.of(List.of(
                        CompoundTag.of("item", mapItem(1)))))).build())));
        CompoundTag renumbered = maps.apply(chest);
        CompoundTag box = (CompoundTag) ((ListTag) renumbered.get("Items")).get(1);
        assertEquals(mapItem(2), ((CompoundTag) ((ListTag) compound(box, "components").get("minecraft:container")).get(0)).get("item"));
        assertEquals(old, ((ListTag) renumbered.get("Items")).get(0), "a map the base already had keeps its id");
        assertTrue(maps.apply(old) == old, "what holds no new map comes back as the same object");
    }

    private static CompoundTag mapItem(int id) {
        return CompoundTag.builder().put("id", new StringTag("minecraft:filled_map"))
                .put("components", CompoundTag.of("minecraft:map_id", new IntTag(id))).build();
    }
}
