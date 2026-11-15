package mycelium.merge;

import mycelium.json.Json;
import mycelium.nbt.CompoundTag;
import mycelium.nbt.IntTag;
import mycelium.nbt.ListTag;
import mycelium.nbt.LongTag;
import mycelium.nbt.NamedTag;
import mycelium.nbt.NbtIo;
import mycelium.nbt.Tag;
import mycelium.report.Category;
import mycelium.report.ReportEntry;
import mycelium.report.Severity;
import mycelium.rules.Rules;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Merges the files of a world outside chunk, entity and POI storage, following the world file
 * rules of the spec for the 26.3 layout.
 *
 * <p>The player and map paths were confirmed in a world saved by the 26.3 client.
 */
final class WorldFiles {

    /** Inside a dimension's folder: its chunk, entity and POI storage, which {@link WorldMerger} handles. */
    private static final Pattern STORAGE = Pattern.compile("(region|entities|poi)/[^/]+");
    private static final String RAIDS = "data/minecraft/raids.dat";
    private static final String TICKETS = "data/minecraft/chunk_tickets.dat";
    private static final Pattern PLAYER_DATA = Pattern.compile("players/data/[^/]+\\.dat");
    private static final Pattern PLAYER_DATA_OLD = Pattern.compile("players/data/[^/]+\\.dat_old");
    private static final Pattern ADVANCEMENTS = Pattern.compile("players/advancements/[^/]+\\.json");
    private static final Pattern STATS = Pattern.compile("players/stats/[^/]+\\.json");

    private final Path base;
    private final Path ours;
    private final Path theirs;
    private final Path out;
    private final Side newer;
    private final Rules rules;
    private final MapIds maps;
    private final PlayerEntities players;
    private final TreeSet<String> dimensionFolders = new TreeSet<>();
    private final String onlyFolder;
    private final String only;
    private final List<ReportEntry> entries;

    /**
     * @param maps       how the older side's new maps are renumbered
     * @param players    the entities that the entity merge left in players' files
     * @param dimensions the id of every dimension of the worlds
     * @param only       the one dimension that is merged, or null if all are. The files of the others
     *                   are the newer side's
     */
    WorldFiles(Path base, Path ours, Path theirs, Path out, Side newer, Rules rules, MapIds maps,
               PlayerEntities players, Set<String> dimensions, String only, List<ReportEntry> entries) {
        this.base = base;
        this.ours = ours;
        this.theirs = theirs;
        this.out = out;
        this.newer = newer;
        this.rules = rules;
        this.maps = maps;
        this.players = players;
        for (String dimension : dimensions) {
            dimensionFolders.add(folder(dimension));
        }
        this.only = only;
        this.onlyFolder = only == null ? null : folder(only);
        this.entries = entries;
    }

    private static String folder(String dimension) {
        String[] id = dimension.split(":", 2);
        return "dimensions/" + id[0] + "/" + id[1] + "/";
    }

    /** The folder of the dimension a path lies in, or null for a file of the world as a whole. */
    private String dimensionFolder(String path) {
        String found = null;
        for (String folder : dimensionFolders) {
            // Dimension ids can nest, so the longest folder that holds the path is the one.
            if (path.startsWith(folder) && (found == null || folder.length() > found.length())) {
                found = folder;
            }
        }
        return found;
    }

    /** Merges every file except region storage and {@code session.lock}. */
    void merge() throws IOException {
        TreeSet<String> paths = new TreeSet<>();
        for (Path world : new Path[] {ours, theirs}) {
            try (Stream<Path> walk = Files.walk(world)) {
                walk.filter(Files::isRegularFile).forEach(file ->
                        paths.add(world.relativize(file).toString().replace('\\', '/')));
            }
        }
        for (String path : paths) {
            String folder = dimensionFolder(path);
            String inDimension = folder == null ? null : path.substring(folder.length());
            if (path.equals("session.lock") || inDimension != null && STORAGE.matcher(inDimension).matches()) {
                continue;
            }
            byte[] merged;
            if (folder != null && onlyFolder != null && !folder.equals(onlyFolder)) {
                merged = read(newerWorld(), path);      // a dimension that is not merged is the newer side's
            } else if (RAIDS.equals(inDimension)) {
                continue;
            } else if (path.equals("level.dat")) {
                merged = levelDat(path);
            } else if (path.equals("data/minecraft/world_clocks.dat")) {
                merged = worldClocks(path);
            } else if (path.equals("data/minecraft/game_rules.dat")) {
                merged = dataFile(path, false);
            } else if (TICKETS.equals(inDimension)) {
                merged = chunkTickets(path);
            } else if (path.equals("data/minecraft/weather.dat") || path.equals("data/minecraft/wandering_trader.dat")
                    || path.equals("data/minecraft/random_sequences.dat")
                    || path.equals("level.dat_old")
                    || path.equals("icon.png") || path.startsWith("datapacks/")) {
                merged = read(newerWorld(), path) != null ? read(newerWorld(), path) : read(olderWorld(), path);
            } else if (ADVANCEMENTS.matcher(path).matches()) {
                merged = json(path, false);
            } else if (STATS.matcher(path).matches()) {
                merged = json(path, true);
            } else if (PLAYER_DATA.matcher(path).matches()) {
                merged = playerData(path);
            } else if (path.equals(MapIds.COUNTER)) {
                merged = mapCounter(path);
            } else if (maps.moves(newer.other(), path)) {
                merged = newMap(path);
            } else {
                // The game's own backup of a player file is no more a conflict than level.dat_old.
                boolean silent = path.startsWith(MapIds.FOLDER) || PLAYER_DATA_OLD.matcher(path).matches();
                merged = wholeFile(path, silent ? null : Category.FILE, false);
            }
            if (merged != null) {
                Path target = out.resolve(path);
                Files.createDirectories(target.getParent());
                Files.write(target, merged);
            }
        }
    }

    private Path newerWorld() {
        return newer == Side.OURS ? ours : theirs;
    }

    private Path olderWorld() {
        return newer == Side.OURS ? theirs : ours;
    }

    private static byte[] read(Path world, String path) throws IOException {
        Path file = world.resolve(path);
        return Files.isRegularFile(file) ? Files.readAllBytes(file) : null;
    }

    /** The decoded NBT of a gzip data file, or null if absent or unreadable. */
    private static NamedTag nbt(Path world, String path) {
        try {
            Path file = world.resolve(path);
            return Files.isRegularFile(file) ? NbtIo.readGzipFile(file) : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static byte[] gzip(NamedTag root) throws IOException {
        Path temp = Files.createTempFile("mycelium", ".dat");
        try {
            NbtIo.writeGzipFile(temp, root, NbtIo.KeyOrder.SORTED);
            return Files.readAllBytes(temp);
        } finally {
            Files.delete(temp);
        }
    }

    /**
     * Whole file 3-way. Data files compare by their NBT, so a file rewritten with the same
     * content does not count as changed.
     *
     * @param category the category to record a conflict under, or null to settle it silently
     * @param backup   whether the losing version is written next to the winner
     */
    private byte[] wholeFile(String path, Category category, boolean backup) throws IOException {
        byte[] was = read(base, path);
        byte[] mine = read(ours, path);
        byte[] other = read(theirs, path);
        Object wasValue = was;
        Object mineValue = mine;
        Object otherValue = other;
        if (path.endsWith(".dat")) {
            NamedTag a = nbt(ours, path);
            NamedTag b = nbt(theirs, path);
            NamedTag o = nbt(base, path);
            if ((a != null || mine == null) && (b != null || other == null) && (o != null || was == null)) {
                wasValue = o;
                mineValue = a;
                otherValue = b;
            }
        }
        boolean same = mineValue instanceof byte[] x && otherValue instanceof byte[] y ? Arrays.equals(x, y)
                : Objects.equals(mineValue, otherValue);
        boolean oursUnchanged = mineValue instanceof byte[] x && wasValue instanceof byte[] y ? Arrays.equals(x, y)
                : Objects.equals(mineValue, wasValue);
        boolean theirsUnchanged = otherValue instanceof byte[] x && wasValue instanceof byte[] y ? Arrays.equals(x, y)
                : Objects.equals(otherValue, wasValue);
        if (same || theirsUnchanged) {
            return mine;
        }
        if (oursUnchanged) {
            return other;
        }
        byte[] winner = newer == Side.OURS ? mine : other;
        byte[] loser = newer == Side.OURS ? other : mine;
        if (category != null) {
            entries.add(new ReportEntry(Severity.CONFLICT, category, null, null, null, null, null, newer, path));
        }
        if (backup && loser != null) {
            Path target = out.resolve(backupPath(path, newer.other()));
            Files.createDirectories(target.getParent());
            Files.write(target, loser);
        }
        return winner;
    }

    /** {@code level.dat}: game time is the maximum, every other field of {@code Data} merges on its own. */
    private byte[] levelDat(String path) throws IOException {
        NamedTag a = nbt(ours, path);
        NamedTag b = nbt(theirs, path);
        NamedTag o = nbt(base, path);
        if (!(a != null && a.tag() instanceof CompoundTag oursRoot && oursRoot.get("Data") instanceof CompoundTag mine)
                || !(b != null && b.tag() instanceof CompoundTag theirsRoot && theirsRoot.get("Data") instanceof CompoundTag other)) {
            return wholeFile(path, Category.LEVEL, false);
        }
        CompoundTag was = o != null && o.tag() instanceof CompoundTag root && root.get("Data") instanceof CompoundTag data
                ? data : null;
        CompoundTag merged = Values.mergeCompound(was, mine, other, newer, key -> {
            if (!key.equals("Time") && !key.equals("LastPlayed")) {
                entries.add(new ReportEntry(Severity.CONFLICT, Category.LEVEL, null, null, text(was, key),
                        text(mine, key), text(other, key), newer, "level.dat " + key));
            }
        });
        for (String key : new String[] {"Time", "LastPlayed"}) {
            if (mine.get(key) instanceof LongTag x && other.get(key) instanceof LongTag y) {
                merged = merged.with(key, new LongTag(Math.max(x.value(), y.value())));
            }
        }
        return gzip(new NamedTag(a.name(), oursRoot.with("Data", merged)));
    }

    private static String text(CompoundTag compound, String key) {
        return compound == null || compound.get(key) == null ? null : compound.get(key).toString();
    }

    /** {@code world_clocks.dat}: each clock's {@code total_ticks} is the maximum of both sides. */
    private byte[] worldClocks(String path) throws IOException {
        NamedTag a = nbt(ours, path);
        NamedTag b = nbt(theirs, path);
        if (!(a != null && a.tag() instanceof CompoundTag oursRoot && oursRoot.get("data") instanceof CompoundTag mine)
                || !(b != null && b.tag() instanceof CompoundTag theirsRoot && theirsRoot.get("data") instanceof CompoundTag other)) {
            return wholeFile(path, Category.FILE, false);
        }
        CompoundTag winner = newer == Side.OURS ? mine : other;
        CompoundTag loser = newer == Side.OURS ? other : mine;
        CompoundTag.Builder clocks = winner.toBuilder();
        for (String clock : new TreeSet<>(loser.keys())) {
            Tag theirsClock = loser.get(clock);
            Tag oursClock = winner.get(clock);
            if (oursClock == null) {
                clocks.put(clock, theirsClock);
            } else if (oursClock instanceof CompoundTag x && theirsClock instanceof CompoundTag y
                    && x.get("total_ticks") instanceof LongTag xt && y.get("total_ticks") instanceof LongTag yt) {
                clocks.put(clock, x.with("total_ticks", new LongTag(Math.max(xt.value(), yt.value()))));
            }
        }
        CompoundTag root = newer == Side.OURS ? oursRoot : theirsRoot;
        return gzip(new NamedTag(a.name(), root.with("data", clocks.build())));
    }

    /**
     * {@code chunk_tickets.dat}: the forced chunks, merged as a set. A ticket either side added is
     * kept, and a ticket either side removed is gone.
     */
    private byte[] chunkTickets(String path) throws IOException {
        NamedTag a = nbt(ours, path);
        NamedTag b = nbt(theirs, path);
        NamedTag o = nbt(base, path);
        if (!(a != null && a.tag() instanceof CompoundTag oursRoot && oursRoot.get("data") instanceof CompoundTag mine)
                || !(b != null && b.tag() instanceof CompoundTag theirsRoot && theirsRoot.get("data") instanceof CompoundTag other)) {
            return wholeFile(path, Category.FILE, false);
        }
        List<Tag> was = tickets(o != null && o.tag() instanceof CompoundTag root && root.get("data") instanceof CompoundTag data
                ? data : null);
        List<Tag> oursTickets = tickets(mine);
        List<Tag> theirsTickets = tickets(other);
        if (was == null || oursTickets == null || theirsTickets == null) {
            return wholeFile(path, Category.FILE, false);
        }
        List<Tag> merged = new java.util.ArrayList<>();
        for (Side side : new Side[] {newer, newer.other()}) {
            List<Tag> own = side == Side.OURS ? oursTickets : theirsTickets;
            List<Tag> others = side == Side.OURS ? theirsTickets : oursTickets;
            for (Tag ticket : own) {
                boolean removedByOther = was.contains(ticket) && !others.contains(ticket);
                if (!removedByOther && !merged.contains(ticket)) {
                    merged.add(ticket);
                }
            }
        }
        CompoundTag root = newer == Side.OURS ? oursRoot : theirsRoot;
        CompoundTag winner = newer == Side.OURS ? mine : other;
        CompoundTag data = merged.isEmpty() ? winner.without("tickets")
                : winner.with("tickets", ListTag.mixed(merged));
        return gzip(new NamedTag(a.name(), root.with("data", data)));
    }

    /** The tickets of a file's {@code data}; empty if it has none, null if the layout is not the known one. */
    private static List<Tag> tickets(CompoundTag data) {
        if (data == null || data.get("tickets") == null) {
            return List.of();
        }
        return data.get("tickets") instanceof mycelium.nbt.ListTag list ? list.elements() : null;
    }

    /** A {@code {DataVersion, data: {...}}} file whose {@code data} entries merge one by one. */
    private byte[] dataFile(String path, boolean record) throws IOException {
        NamedTag a = nbt(ours, path);
        NamedTag b = nbt(theirs, path);
        NamedTag o = nbt(base, path);
        if (!(a != null && a.tag() instanceof CompoundTag oursRoot && oursRoot.get("data") instanceof CompoundTag mine)
                || !(b != null && b.tag() instanceof CompoundTag theirsRoot && theirsRoot.get("data") instanceof CompoundTag other)) {
            return wholeFile(path, Category.FILE, false);
        }
        CompoundTag was = o != null && o.tag() instanceof CompoundTag root && root.get("data") instanceof CompoundTag data
                ? data : null;
        CompoundTag merged = Values.mergeCompound(was, mine, other, newer, key -> {
            if (record) {
                entries.add(new ReportEntry(Severity.CONFLICT, Category.FILE, null, null, text(was, key), text(mine, key),
                        text(other, key), newer, path + " " + key));
            }
        });
        CompoundTag root = newer == Side.OURS ? oursRoot : theirsRoot;
        return gzip(new NamedTag(a.name(), root.with("data", merged)));
    }

    /**
     * A player's file. It is one value: the side that changed it wins, and the newer side if both
     * did, with the other side's file written next to it as a backup. Three things inside it
     * follow rules of their own:
     *
     * <ul>
     * <li>The recipe book merges as a set when both sides changed the file. The advancements that
     *     unlock recipes are merged as a union, and the game does not unlock a recipe again for
     *     an advancement that is already done.
     * <li>A file taken from the older side has that side's new maps renumbered; see {@link MapIds}.
     * <li>The vehicle the player left the game in is the one the entity merge decided on; see
     *     {@link PlayerEntities}.
     * </ul>
     */
    private byte[] playerData(String path) throws IOException {
        String name = path.substring(path.lastIndexOf('/') + 1);
        PlayerEntities.Versions versions = PlayerEntities.versions(base, ours, theirs, name, newer, only);
        if (versions == null) {
            return wholeFile(path, Category.PLAYER, true);   // neither side's file can be read: one of them, as bytes
        }
        for (Side side : versions.unreadable()) {
            entries.add(new ReportEntry(Severity.REFUSED, Category.REFUSED, null, null, null, null, null, null,
                    path + " of " + side.label() + " cannot be read; that side counts as not having changed it"));
        }
        if (versions.baseUnreadable()) {
            entries.add(new ReportEntry(Severity.REFUSED, Category.REFUSED, null, null, null, null, null, null,
                    path + " of the base cannot be read; the file is merged without an ancestor"));
        }
        Side side = versions.taken();
        CompoundTag was = versions.base();
        CompoundTag taken = versions.of(side);
        CompoundTag left = versions.of(side.other());
        boolean same = Objects.equals(taken, left);
        boolean bothChanged = !same && !Objects.equals(left, was);
        if (bothChanged) {
            entries.add(new ReportEntry(Severity.CONFLICT, Category.PLAYER, null, null, null, null, null, side, path));
            if (left != null) {
                CompoundTag backup = maps.renumbers(side.other()) ? maps.apply(left) : left;
                boolean asStored = backup == left && !versions.unreadable().contains(side.other());
                Path target = out.resolve(backupPath(path, side.other()));
                Files.createDirectories(target.getParent());
                Files.write(target, asStored ? read(side == Side.OURS ? theirs : ours, path) : gzip(NamedTag.of(backup)));
            }
        }
        if (taken == null) {
            return null;
        }
        // A file that is the same on both sides is left as it is, map ids included.
        CompoundTag file = same || !maps.renumbers(side) ? taken : maps.apply(taken);
        if (bothChanged && left != null) {
            file = withRecipes(file, was, left);
        }
        UUID player = PlayerEntities.player(name);
        if (player != null) {
            CompoundTag settled = players.apply(player, file);
            for (String key : file.keys()) {
                if (!settled.contains(key)) {
                    entries.add(new ReportEntry(Severity.RESOLVED, Category.PLAYER, null, null, null, null, null, null,
                            path + ": the entity under " + key + " is not kept in the player's file"));
                }
            }
            file = settled;
        }
        // An untouched file is written as the game stored it.
        boolean asStored = file == taken && !versions.unreadable().contains(side);
        return asStored ? read(side == Side.OURS ? ours : theirs, path) : gzip(NamedTag.of(file));
    }

    /**
     * Where the losing version of a file is kept: next to the winner, under a number that no
     * input uses yet. An input can hold backups of earlier merges, and those stay as they are.
     */
    private String backupPath(String path, Side loser) {
        for (int number = 1; ; number++) {
            String candidate = path + "." + loser.label() + "-backup-" + number;
            if (!Files.exists(base.resolve(candidate)) && !Files.exists(ours.resolve(candidate))
                    && !Files.exists(theirs.resolve(candidate))) {
                return candidate;
            }
        }
    }

    /** The player file with the two lists of its recipe book merged 3-way as sets with the other side's. */
    private static CompoundTag withRecipes(CompoundTag file, CompoundTag was, CompoundTag left) {
        if (!(file.get("recipeBook") instanceof CompoundTag book) || !(left.get("recipeBook") instanceof CompoundTag other)) {
            return file;
        }
        CompoundTag before = was != null && was.get("recipeBook") instanceof CompoundTag compound ? compound : CompoundTag.empty();
        CompoundTag merged = book;
        for (String list : new String[] {"recipes", "toBeDisplayed"}) {
            if (book.get(list) instanceof ListTag mine && other.get(list) instanceof ListTag theirs) {
                List<Tag> known = before.get(list) instanceof ListTag old ? old.elements() : List.of();
                List<Tag> result = new java.util.ArrayList<>();
                for (Tag recipe : mine) {
                    if (!known.contains(recipe) || theirs.elements().contains(recipe)) {
                        result.add(recipe);     // not one the other side removed
                    }
                }
                for (Tag recipe : theirs) {
                    if (!known.contains(recipe) && !mine.elements().contains(recipe)) {
                        result.add(recipe);     // one the other side added
                    }
                }
                if (!result.equals(mine.elements())) {
                    merged = merged.with(list, ListTag.mixed(result));
                }
            }
        }
        return merged == book ? file : file.with("recipeBook", merged);
    }

    /** {@code last_id.dat}: the counter that numbers maps holds the highest id in the merged world. */
    private byte[] mapCounter(String path) throws IOException {
        Path world = read(newerWorld(), path) != null ? newerWorld() : olderWorld();
        NamedTag root = nbt(world, path);
        if (!(root != null && root.tag() instanceof CompoundTag compound && compound.get("data") instanceof CompoundTag data
                && data.get("map") instanceof IntTag last)) {
            return wholeFile(path, null, false);
        }
        return last.value() == maps.last() ? read(world, path)
                : gzip(new NamedTag(root.name(), compound.with("data", data.with("map", new IntTag(maps.last())))));
    }

    /**
     * A map that the older side created under an id the newer side's maps also use. The newer
     * side's map keeps the id and the older side's gets the next free one.
     */
    private byte[] newMap(String path) throws IOException {
        byte[] moved = read(olderWorld(), path);
        if (moved != null) {
            String target = maps.moved(path);
            Files.createDirectories(out.resolve(target).getParent());
            Files.write(out.resolve(target), moved);
            entries.add(new ReportEntry(Severity.RESOLVED, Category.FILE, null, null, null, null, null, null,
                    path + " of " + newer.other().label() + " is now " + target));
        }
        return read(newerWorld(), path);
    }

    /**
     * A player's JSON file. Advancements: the union of everything either side completed.
     * Statistics: every counter is the base's number plus what each side added to it.
     */
    private byte[] json(String path, boolean statistics) throws IOException {
        byte[] mine = read(ours, path);
        byte[] other = read(theirs, path);
        if (mine == null || other == null) {
            return wholeFile(path, Category.PLAYER, false);
        }
        try {
            Object a = Json.parse(new String(mine, StandardCharsets.UTF_8));
            Object b = Json.parse(new String(other, StandardCharsets.UTF_8));
            Object merged;
            if (statistics) {
                byte[] was = read(base, path);
                Object o = was == null ? null : Json.parse(new String(was, StandardCharsets.UTF_8));
                merged = newer == Side.OURS ? statistics(o, a, b, null) : statistics(o, b, a, null);
            } else {
                merged = newer == Side.OURS ? union(a, b) : union(b, a);
            }
            return Json.write(merged).getBytes(StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return wholeFile(path, Category.PLAYER, false);
        }
    }

    /** Deep union of two JSON values. Where both have a value, {@code true} beats {@code false} and the first wins otherwise. */
    private static Object union(Object first, Object second) {
        if (first instanceof Map<?, ?> a && second instanceof Map<?, ?> b) {
            Map<String, Object> merged = new LinkedHashMap<>();
            for (String key : keys(a, b)) {
                boolean both = a.containsKey(key) && b.containsKey(key);
                merged.put(key, both ? union(a.get(key), b.get(key)) : a.containsKey(key) ? a.get(key) : b.get(key));
            }
            return merged;
        }
        if (first instanceof Boolean x && second instanceof Boolean y) {
            return x || y;
        }
        return first;
    }

    private static TreeSet<String> keys(Map<?, ?> a, Map<?, ?> b) {
        TreeSet<String> keys = new TreeSet<>();
        a.keySet().forEach(key -> keys.add(String.valueOf(key)));
        b.keySet().forEach(key -> keys.add(String.valueOf(key)));
        return keys;
    }

    /**
     * Merges a statistics file. The numbers under {@code stats} are running totals, so the merged
     * number is the base's plus what each side added: {@code newer + older - base}. A number that
     * went down on a side is not a total; then the larger one is taken. The timers the rules list
     * restart at zero, so they merge as plain values: the side that changed one wins, and the
     * newer side if both did.
     *
     * @param stat where the value is under {@code stats}: {@code ""} for {@code stats} itself, then
     *             {@code group} and {@code group/name}; null outside {@code stats}
     */
    private Object statistics(Object was, Object first, Object second, String stat) {
        if (first instanceof Map<?, ?> a && second instanceof Map<?, ?> b) {
            Map<?, ?> o = was instanceof Map<?, ?> map ? map : Map.of();
            Map<String, Object> merged = new LinkedHashMap<>();
            for (String key : keys(a, b)) {
                String inside = stat == null ? key.equals("stats") ? "" : null : stat.isEmpty() ? key : stat + "/" + key;
                boolean both = a.containsKey(key) && b.containsKey(key);
                merged.put(key, both ? statistics(o.get(key), a.get(key), b.get(key), inside)
                        : a.containsKey(key) ? a.get(key) : b.get(key));
            }
            return merged;
        }
        if (stat == null || !(first instanceof Number x) || !(second instanceof Number y)
                || x instanceof Double || y instanceof Double) {
            return first;
        }
        long before = was instanceof Number number && !(number instanceof Double) ? number.longValue() : 0;
        if (rules.statTimers().contains(stat)) {
            return x.longValue() != before || y.longValue() == before ? first : second;
        }
        long total = x.longValue() >= before && y.longValue() >= before
                ? x.longValue() + y.longValue() - before : Math.max(x.longValue(), y.longValue());
        // The game stores statistics as 32-bit numbers.
        return (int) Math.min(total, Integer.MAX_VALUE);
    }
}
