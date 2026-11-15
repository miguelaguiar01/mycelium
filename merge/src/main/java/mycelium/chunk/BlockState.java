package mycelium.chunk;

import mycelium.nbt.CompoundTag;
import mycelium.nbt.ListTag;
import mycelium.nbt.StringTag;
import mycelium.nbt.Tag;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * A block state as Minecraft 26.3 stores it: a block id and its properties, sorted by key.
 *
 * <p><b>Empty properties mean the block's default state</b>, not "no properties". 26.3 writes a
 * default state as a bare string such as {@code "minecraft:chest"}, and any other state as
 * {@code {id, properties}} with every property listed. It never writes a default state the long
 * way, so two states written by the game are the same state exactly when they are equal here.
 * Telling what the default's property values are needs a block table, which this class does not
 * have.
 */
public record BlockState(String name, SortedMap<String, String> properties) {

    private static final String ID = "id";
    private static final String PROPERTIES = "properties";

    public BlockState {
        Objects.requireNonNull(name, "name");
        properties = Collections.unmodifiableSortedMap(new TreeMap<>(properties));
    }

    /** The default state of block {@code name}. */
    public static BlockState of(String name) {
        return new BlockState(name, Collections.emptySortedMap());
    }

    public static BlockState of(String name, Map<String, String> properties) {
        return new BlockState(name, new TreeMap<>(properties));
    }

    /** Whether this is the short form: the block's default state. */
    public boolean isDefault() {
        return properties.isEmpty();
    }

    /** Parses {@code name} or {@code name[key=value,...]}, the form {@link #toString} gives. */
    public static BlockState parse(String text) {
        int bracket = text.indexOf('[');
        if (bracket < 0) {
            return of(text);
        }
        if (!text.endsWith("]")) {
            throw new IllegalArgumentException("not a block state: " + text);
        }
        SortedMap<String, String> properties = new TreeMap<>();
        for (String pair : text.substring(bracket + 1, text.length() - 1).split(",")) {
            int equals = pair.indexOf('=');
            if (equals <= 0) {
                throw new IllegalArgumentException("not a block state: " + text);
            }
            properties.put(pair.substring(0, equals), pair.substring(equals + 1));
        }
        return new BlockState(text.substring(0, bracket), properties);
    }

    /** Decodes a palette entry or any other stored block state, wrapped or not. */
    public static BlockState fromTag(Tag tag, String where) throws ChunkFormatException {
        Tag unwrapped = ListTag.unwrap(tag);
        if (unwrapped instanceof StringTag string) {
            return of(string.value());
        }
        if (!(unwrapped instanceof CompoundTag compound)) {
            throw new ChunkFormatException(where + ": block state is a " + unwrapped.type());
        }
        for (String key : compound.keys()) {
            if (!key.equals(ID) && !key.equals(PROPERTIES)) {
                throw new ChunkFormatException(where + ": block state has unknown field " + key);
            }
        }
        String name = Fields.requiredString(compound, ID, where);
        CompoundTag stored = Fields.optionalCompound(compound, PROPERTIES, where);
        SortedMap<String, String> properties = new TreeMap<>();
        if (stored != null) {
            for (Map.Entry<String, Tag> property : stored.entries().entrySet()) {
                if (!(property.getValue() instanceof StringTag value)) {
                    throw new ChunkFormatException(where + ": property " + property.getKey() + " of " + name
                            + " is a " + property.getValue().type());
                }
                properties.put(property.getKey(), value.value());
            }
        }
        return new BlockState(name, properties);
    }

    /** Encodes this state as 26.3 does: a string for a default state, otherwise a compound. */
    public Tag toTag() {
        if (properties.isEmpty()) {
            return new StringTag(name);
        }
        CompoundTag.Builder stored = CompoundTag.builder();
        properties.forEach((key, value) -> stored.put(key, new StringTag(value)));
        return CompoundTag.builder().put(ID, new StringTag(name)).put(PROPERTIES, stored.build()).build();
    }

    @Override
    public String toString() {
        if (properties.isEmpty()) {
            return name;
        }
        StringBuilder text = new StringBuilder(name).append('[');
        properties.forEach((key, value) -> text.append(key).append('=').append(value).append(','));
        text.setCharAt(text.length() - 1, ']');
        return text.toString();
    }
}
