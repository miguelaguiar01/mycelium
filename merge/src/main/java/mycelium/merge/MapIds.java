package mycelium.merge;

import mycelium.chunk.BlockPos;
import mycelium.chunk.ChunkData;
import mycelium.chunk.FlatEntity;
import mycelium.nbt.CompoundTag;
import mycelium.nbt.IntTag;
import mycelium.nbt.ListTag;
import mycelium.nbt.NamedTag;
import mycelium.nbt.NbtIo;
import mycelium.nbt.Tag;
import mycelium.nbt.TagType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Renumbers the maps the older side created since the base.
 *
 * <p>The game numbers maps from one counter, {@code data/minecraft/maps/last_id.dat}. Two worlds
 * that started from the same base therefore give their next map the same id, for two different
 * maps. If both sides created maps, the older side's new maps are moved behind the newer side's:
 * map {@code n} becomes {@code n + shift}. That applies to the map's file and to every item that
 * shows the map, which names it in its {@code minecraft:map_id} component.
 *
 * <p>Seen in a real 26.3 world: {@code maps/<id>.dat} per map, and the counter as
 * {@code {data: {map: <last id>}}}.
 */
final class MapIds {

    static final String FOLDER = "data/minecraft/maps/";
    static final String COUNTER = FOLDER + "last_id.dat";
    private static final Pattern FILE = Pattern.compile(Pattern.quote(FOLDER) + "(\\d{1,9})\\.dat");
    private static final String COMPONENT = "minecraft:map_id";

    private final Side side;    // the side whose new maps are renumbered, or null if none are
    private final int first;    // the lowest id that side gave out since the base
    private final int shift;
    private final int last;     // the highest id in the merged world, or -1 if it has no map

    private MapIds(Side side, int first, int shift, int last) {
        this.side = side;
        this.first = first;
        this.shift = shift;
        this.last = last;
    }

    /** Works out the renumbering from the three worlds' counters. */
    static MapIds read(Path base, Path ours, Path theirs, Side newer) throws IOException {
        int was = lastId(base);
        int mine = lastId(ours);
        int other = lastId(theirs);
        if (mine <= was || other <= was) {
            return new MapIds(null, 0, 0, Math.max(mine, other));
        }
        int kept = newer == Side.OURS ? mine : other;
        return new MapIds(newer.other(), was + 1, kept - was, mine + other - was);
    }

    /** The highest map id a world has given out: its counter, or its highest map file if that is higher. */
    private static int lastId(Path world) throws IOException {
        int last = -1;
        Path counter = world.resolve(COUNTER);
        if (Files.isRegularFile(counter)) {
            try {
                NamedTag root = NbtIo.readGzipFile(counter);
                if (root.tag() instanceof CompoundTag compound && compound.get("data") instanceof CompoundTag data
                        && data.get("map") instanceof IntTag id) {
                    last = id.value();
                }
            } catch (IOException e) {
                // An unreadable counter says nothing; the map files below still do.
            }
        }
        Path folder = world.resolve(FOLDER);
        if (Files.isDirectory(folder)) {
            try (Stream<Path> files = Files.list(folder)) {
                for (Path file : files.toList()) {
                    last = Math.max(last, idOf(FOLDER + file.getFileName()));
                }
            }
        }
        return last;
    }

    /** The map id of a world-relative path if it is a map's file, or -1. */
    static int idOf(String path) {
        Matcher matcher = FILE.matcher(path);
        return matcher.matches() ? Integer.parseInt(matcher.group(1)) : -1;
    }

    /** The highest map id in the merged world, or -1 if it has no map. */
    int last() {
        return last;
    }

    /** Whether data that comes from {@code side} holds map ids that must be renumbered. */
    boolean renumbers(Side side) {
        return this.side == side;
    }

    /** Whether {@code side}'s map file at this path gets another name in the merged world. */
    boolean moves(Side side, String path) {
        return renumbers(side) && idOf(path) >= first;
    }

    /** The path of the renumbered side's map file in the merged world. */
    String moved(String path) {
        return FOLDER + (idOf(path) + shift) + ".dat";
    }

    /** The tag with every map id the renumbered side created moved; the same object if it holds none. */
    CompoundTag apply(CompoundTag tag) {
        return side == null ? tag : (CompoundTag) renumber(tag);
    }

    /** A chunk of the renumbered side; the same object if nothing in it names a new map. */
    ChunkData apply(ChunkData chunk) {
        if (side == null || chunk == null) {
            return chunk;
        }
        Map<BlockPos, CompoundTag> blockEntities = new LinkedHashMap<>();
        boolean changed = false;
        for (Map.Entry<BlockPos, CompoundTag> entry : chunk.blockEntities().entrySet()) {
            CompoundTag renumbered = apply(entry.getValue());
            changed |= renumbered != entry.getValue();
            blockEntities.put(entry.getKey(), renumbered);
        }
        return !changed ? chunk : new ChunkData(chunk.dataVersion(), chunk.pos(), chunk.minSectionY(), chunk.status(),
                chunk.lastUpdate(), chunk.inhabitedTime(), chunk.sections(), blockEntities, chunk.blockTicks(),
                chunk.fluidTicks(), chunk.heightmaps(), chunk.lightOn(), chunk.structures(), chunk.postProcessing(),
                chunk.extra());
    }

    /** An entity of the renumbered side; the same object if nothing in it names a new map. */
    FlatEntity apply(FlatEntity entity) {
        CompoundTag data = apply(entity.data());
        return data == entity.data() ? entity
                : new FlatEntity(entity.uuid(), entity.vehicle(), entity.passengers(), data, entity.carrier());
    }

    private Tag renumber(Tag tag) {
        if (tag instanceof CompoundTag compound) {
            CompoundTag.Builder changed = null;
            for (Map.Entry<String, Tag> entry : compound.entries().entrySet()) {
                Tag value = entry.getValue();
                Tag renumbered = entry.getKey().equals(COMPONENT) && value instanceof IntTag id
                        ? id.value() >= first ? new IntTag(id.value() + shift) : value
                        : renumber(value);
                if (renumbered != value) {
                    changed = changed == null ? compound.toBuilder() : changed;
                    changed.put(entry.getKey(), renumbered);
                }
            }
            return changed == null ? compound : changed.build();
        }
        if (tag instanceof ListTag list && (list.elementType() == TagType.COMPOUND || list.elementType() == TagType.LIST)) {
            List<Tag> elements = null;
            for (int i = 0; i < list.size(); i++) {
                Tag renumbered = renumber(list.get(i));
                if (renumbered != list.get(i)) {
                    elements = elements == null ? new ArrayList<>(list.elements()) : elements;
                    elements.set(i, renumbered);
                }
            }
            return elements == null ? list : ListTag.of(list.elementType(), elements);
        }
        return tag;
    }
}
