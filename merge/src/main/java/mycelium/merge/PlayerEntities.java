package mycelium.merge;

import mycelium.chunk.ChunkFormatException;
import mycelium.chunk.Entities;
import mycelium.chunk.EntityIndex;
import mycelium.chunk.FlatEntity;
import mycelium.nbt.CompoundTag;
import mycelium.nbt.IntArrayTag;
import mycelium.nbt.ListTag;
import mycelium.nbt.NamedTag;
import mycelium.nbt.NbtIo;
import mycelium.nbt.StringTag;
import mycelium.nbt.Tag;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * The player files of the three worlds, and the entities that players took along into them.
 *
 * <p>A player who leaves the game while riding is saved with the vehicle inside the player's
 * file, as {@code RootVehicle: {Attach, Entity}}: {@code Entity} is the vehicle at the bottom of
 * the stack with its other riders nested inside it, and {@code Attach} is the UUID of the entity
 * the player sits on. A parrot on a shoulder is saved as {@code ShoulderEntityLeft} or
 * {@code ShoulderEntityRight}. Either way the entity is no longer in the entity files.
 * {@code RootVehicle} was seen in a real 26.3 world; the shoulder tags are from the game's jar.
 *
 * <p>To the entity merge, such an entity is that side's version of it, changed by being taken
 * along. Otherwise a boat one side left the game in would look removed, and would be lost if
 * that side's player file is not the one written, or stored twice if the other side moved it.
 * After the merge, an entity stays in the player's file only if that file is the one written
 * and, for a vehicle, the player's seat is still in it. Any other surviving entity goes back into
 * the entity files, and a written player file whose entity did not win is written without it.
 *
 * <p>This class also decides which side's version of a player file is written, so that the
 * entity merge and {@link WorldFiles} cannot disagree about it.
 */
final class PlayerEntities {

    static final String FOLDER = "players/data";
    static final String VEHICLE = "RootVehicle";
    static final List<String> SHOULDERS = List.of("ShoulderEntityLeft", "ShoulderEntityRight");
    private static final String ENTITY = "Entity";
    private static final String DEFAULT_DIMENSION = "minecraft:overworld";

    /**
     * The versions of one player's file as the merge treats them. A version that cannot be read
     * counts as unchanged: the base's stands in for it, or the other side's if the base has none.
     *
     * @param base       the base's file, or null if it has none or it cannot be read
     * @param unreadable the sides whose file is there but cannot be read
     * @param taken      the side whose version is written
     */
    record Versions(CompoundTag base, CompoundTag ours, CompoundTag theirs, Set<Side> unreadable, boolean baseUnreadable,
                    Side taken) {

        CompoundTag of(Side side) {
            return side == Side.OURS ? ours : theirs;
        }
    }

    /** What the file that will be written for a player holds: each entity by the tag it is in, and where. */
    private record Held(Map<UUID, String> tags, UUID seat, String dimension) {
    }

    private final Map<String, List<FlatEntity>> inBase = new HashMap<>();
    private final Map<String, List<FlatEntity>> inOurs = new HashMap<>();
    private final Map<String, List<FlatEntity>> inTheirs = new HashMap<>();
    private final Map<UUID, Held> written = new HashMap<>();
    private final Set<UUID> players = new HashSet<>();
    /** Per merged dimension and player, the entity that stays under each tag of the player's file. */
    private final Map<String, Map<UUID, Map<String, CompoundTag>>> kept = new HashMap<>();

    private PlayerEntities() {
    }

    /**
     * Reads the entities out of the player files of the three worlds.
     *
     * @param dimension the one dimension that is merged, or null if all are
     */
    static PlayerEntities read(Path base, Path ours, Path theirs, Side newer, String dimension) throws IOException {
        PlayerEntities held = new PlayerEntities();
        SortedSet<String> names = new TreeSet<>();
        for (Path world : new Path[] {ours, theirs}) {
            Path folder = world.resolve(FOLDER);
            if (Files.isDirectory(folder)) {
                try (Stream<Path> files = Files.list(folder)) {
                    files.map(file -> file.getFileName().toString()).filter(name -> name.endsWith(".dat")).forEach(names::add);
                }
            }
        }
        for (String name : names) {
            UUID player = player(name);
            if (player == null) {
                continue;
            }
            held.players.add(player);
            Versions versions = versions(base, ours, theirs, name, newer, dimension);
            if (versions == null) {
                continue;   // neither side's file can be read; it is merged as a whole file, entities and all
            }
            add(held.inBase, versions.base(), player, null);
            add(held.inOurs, versions.ours(), player, null);
            add(held.inTheirs, versions.theirs(), player, null);
            CompoundTag taken = versions.of(versions.taken());
            if (taken != null) {
                Map<UUID, String> tags = new LinkedHashMap<>();
                add(new HashMap<>(), taken, player, tags);
                UUID seat = taken.get(VEHICLE) instanceof CompoundTag root && root.get("Attach") instanceof IntArrayTag attach
                        && attach.size() == 4 ? Entities.toUuid(attach.ints()) : null;
                held.written.put(player, new Held(tags, seat, dimension(taken)));
            }
        }
        return held;
    }

    /**
     * Reads the three versions of a player's file and decides which is written. Null if neither
     * side has a version that can be read.
     *
     * @param only the one dimension that is merged, or null if all are. A player whom either side
     *             has in another dimension is taken from the newer side, as that dimension is
     */
    static Versions versions(Path base, Path ours, Path theirs, String name, Side newer, String only) {
        Object was = file(base, name);
        Object mine = file(ours, name);
        Object other = file(theirs, name);
        Set<Side> unreadable = EnumSet.noneOf(Side.class);
        if (mine instanceof IOException) {
            unreadable.add(Side.OURS);
        }
        if (other instanceof IOException) {
            unreadable.add(Side.THEIRS);
        }
        if (unreadable.size() == 2 || unreadable.size() == 1 && (mine == null || other == null) && !(was instanceof CompoundTag)) {
            return null;
        }
        CompoundTag baseFile = was instanceof CompoundTag tag ? tag : null;
        CompoundTag oursFile = mine instanceof CompoundTag tag ? tag
                : mine == null ? null : baseFile != null ? baseFile : (CompoundTag) other;
        CompoundTag theirsFile = other instanceof CompoundTag tag ? tag
                : other == null ? null : baseFile != null ? baseFile : (CompoundTag) mine;
        Side taken = taken(baseFile, oursFile, theirsFile, newer);
        if (only != null && (oursFile != null && !only.equals(dimension(oursFile))
                || theirsFile != null && !only.equals(dimension(theirsFile)))) {
            taken = (newer == Side.OURS ? oursFile : theirsFile) != null ? newer : newer.other();
        }
        return new Versions(baseFile, oursFile, theirsFile, unreadable, was instanceof IOException, taken);
    }

    /** The player a file in {@code players/data} belongs to, or null if its name is not a UUID. */
    static UUID player(String fileName) {
        try {
            return UUID.fromString(fileName.substring(0, fileName.length() - ".dat".length()));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Which side's version of a value is the one to write: the side that changed it, and the newer
     * side if both did. Null values stand for a missing file.
     */
    static Side taken(Object base, Object ours, Object theirs, Side newer) {
        if (Objects.equals(ours, theirs) || Objects.equals(theirs, base)) {
            return Side.OURS;
        }
        return Objects.equals(ours, base) ? Side.THEIRS : newer;
    }

    /** A player's file: its root compound, null if there is none, or the error if it cannot be read. */
    private static Object file(Path world, String name) {
        Path file = world.resolve(FOLDER).resolve(name);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            NamedTag root = NbtIo.readGzipFile(file);
            return root.tag() instanceof CompoundTag compound ? compound : new IOException(file + " is not a compound");
        } catch (IOException e) {
            return e;
        }
    }

    private static String dimension(CompoundTag player) {
        return player.get("Dimension") instanceof StringTag dimension ? dimension.value() : DEFAULT_DIMENSION;
    }

    /**
     * Adds the entities a player's file holds, flattened, to their dimension. An entity that
     * cannot be read as one stays in the file untouched.
     *
     * @param tags if given, receives the tag each entity is under
     */
    private static void add(Map<String, List<FlatEntity>> byDimension, CompoundTag player, UUID carrier,
                            Map<UUID, String> tags) {
        if (player == null) {
            return;
        }
        Map<String, CompoundTag> entities = new LinkedHashMap<>();
        if (player.get(VEHICLE) instanceof CompoundTag root && root.get(ENTITY) instanceof CompoundTag entity) {
            entities.put(VEHICLE, entity);
        }
        for (String shoulder : SHOULDERS) {
            if (player.get(shoulder) instanceof CompoundTag entity && entity.contains("UUID")) {
                entities.put(shoulder, entity);
            }
        }
        for (Map.Entry<String, CompoundTag> entry : entities.entrySet()) {
            try {
                List<FlatEntity> flat = new ArrayList<>(Entities.flatten(List.of(entry.getValue())));
                flat.set(0, flat.get(0).carriedBy(carrier));
                byDimension.computeIfAbsent(dimension(player), key -> new ArrayList<>()).addAll(flat);
                if (tags != null) {
                    tags.put(flat.get(0).uuid(), entry.getKey());
                }
            } catch (ChunkFormatException e) {
                // Not an entity the merge can place: it is left where it is.
            }
        }
    }

    /** The entities that one world's player files hold in a dimension. {@code side} null asks for the base. */
    List<FlatEntity> carried(Side side, String dimension) {
        Map<String, List<FlatEntity>> world = side == null ? inBase : side == Side.OURS ? inOurs : inTheirs;
        return world.getOrDefault(dimension, List.of());
    }

    /** Whether a UUID is a player's: one with a file in {@code players/data} on either side. */
    boolean isPlayer(UUID uuid) {
        return players.contains(uuid);
    }

    /** Whether the file that will be written for the entity's player holds this entity. */
    boolean holds(FlatEntity entity) {
        Held held = written.get(entity.carrier());
        return held != null && held.tags().containsKey(entity.uuid());
    }

    /**
     * Takes note of the entities that the merged entities of a dimension leave with their players.
     *
     * @return the entities, with any vehicle that its player no longer sits in given back to the
     *         entity files
     */
    EntityIndex settle(String dimension, EntityIndex merged) throws ChunkFormatException {
        Map<UUID, Map<String, CompoundTag>> staying = new HashMap<>();
        Set<UUID> released = new HashSet<>();
        for (Map.Entry<UUID, List<CompoundTag>> player : merged.carried().entrySet()) {
            Held held = written.get(player.getKey());
            for (CompoundTag entity : player.getValue()) {
                UUID uuid = Entities.uuidOf(entity);
                String tag = held == null ? null : held.tags().get(uuid);
                if (tag == null || tag.equals(VEHICLE) && (held.seat() == null || !seats(entity, held.seat()))) {
                    released.add(uuid);
                } else {
                    staying.computeIfAbsent(player.getKey(), key -> new HashMap<>()).put(tag, entity);
                }
            }
        }
        kept.put(dimension, staying);
        if (released.isEmpty()) {
            return merged;
        }
        List<FlatEntity> entities = new ArrayList<>();
        for (FlatEntity entity : merged.entities().values()) {
            entities.add(released.contains(entity.uuid()) ? entity.carriedBy(null) : entity);
        }
        return EntityIndex.of(entities);
    }

    /** Forgets what {@link #settle} noted for a dimension whose entities could not be merged. */
    void unsettle(String dimension) {
        kept.remove(dimension);
    }

    private static boolean seats(CompoundTag vehicle, UUID seat) throws ChunkFormatException {
        if (Entities.uuidOf(vehicle).equals(seat)) {
            return true;
        }
        if (vehicle.get("Passengers") instanceof ListTag riders) {
            for (Tag rider : riders) {
                if (rider instanceof CompoundTag compound && seats(compound, seat)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The player file to write, with the entities that the entity merge left in it: the merged
     * entity under each tag, or none if it went elsewhere. A file of a player in a dimension that
     * was not merged is returned as it is.
     */
    CompoundTag apply(UUID player, CompoundTag file) {
        Held held = written.get(player);
        if (held == null || !kept.containsKey(held.dimension())) {
            return file;
        }
        Map<String, CompoundTag> staying = kept.get(held.dimension()).getOrDefault(player, Map.of());
        CompoundTag result = file;
        for (String tag : held.tags().values()) {
            CompoundTag entity = staying.get(tag);
            if (entity == null) {
                result = result.without(tag);
            } else if (tag.equals(VEHICLE)) {
                CompoundTag root = (CompoundTag) result.get(VEHICLE);
                result = entity.equals(root.get(ENTITY)) ? result : result.with(VEHICLE, root.with(ENTITY, entity));
            } else if (!entity.equals(result.get(tag))) {
                result = result.with(tag, entity);
            }
        }
        return result;
    }
}
