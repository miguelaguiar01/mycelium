package mycelium.nbt;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A map from names to tags.
 *
 * <p>Keys keep the order they were read or added in, so that writing an unchanged tag gives back
 * the bytes it was read from. That order carries no meaning in NBT and is ignored by
 * {@link #equals}.
 */
public final class CompoundTag implements Tag {

    private static final CompoundTag EMPTY = new CompoundTag(Map.of());

    private final Map<String, Tag> entries;

    private CompoundTag(Map<String, Tag> entries) {
        this.entries = entries;
    }

    public static CompoundTag empty() {
        return EMPTY;
    }

    public static CompoundTag of(String key, Tag value) {
        return builder().put(key, value).build();
    }

    /** A compound holding {@code entries} in their iteration order. */
    public static CompoundTag of(Map<String, ? extends Tag> entries) {
        return entries.isEmpty() ? EMPTY : new CompoundTag(Collections.unmodifiableMap(new LinkedHashMap<>(entries)));
    }

    public static Builder builder() {
        return new Builder();
    }

    /** The tag stored under {@code key}, or null. */
    public Tag get(String key) {
        return entries.get(key);
    }

    public boolean contains(String key) {
        return entries.containsKey(key);
    }

    public int size() {
        return entries.size();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /** Keys in stored order. Unmodifiable. */
    public Set<String> keys() {
        return entries.keySet();
    }

    /** Entries in stored order. Unmodifiable. */
    public Map<String, Tag> entries() {
        return entries;
    }

    /** A copy with {@code key} set. An existing key keeps its place; a new one goes last. */
    public CompoundTag with(String key, Tag value) {
        return toBuilder().put(key, value).build();
    }

    /** A copy without the given keys. */
    public CompoundTag without(String... keys) {
        Builder builder = toBuilder();
        for (String key : keys) {
            builder.remove(key);
        }
        return builder.build();
    }

    public Builder toBuilder() {
        Builder builder = new Builder();
        builder.entries.putAll(entries);
        return builder;
    }

    @Override
    public TagType type() {
        return TagType.COMPOUND;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CompoundTag that && entries.equals(that.entries);
    }

    @Override
    public int hashCode() {
        return entries.hashCode();
    }

    @Override
    public String toString() {
        return Snbt.format(this);
    }

    public static final class Builder {

        private final Map<String, Tag> entries = new LinkedHashMap<>();

        private Builder() {
        }

        public Builder put(String key, Tag value) {
            entries.put(Objects.requireNonNull(key, "key"), Objects.requireNonNull(value, "value"));
            return this;
        }

        /** Puts {@code value} unless it is null. */
        public Builder putIfPresent(String key, Tag value) {
            return value == null ? this : put(key, value);
        }

        public Builder putAll(CompoundTag other) {
            entries.putAll(other.entries);
            return this;
        }

        public Builder remove(String key) {
            entries.remove(key);
            return this;
        }

        public CompoundTag build() {
            return CompoundTag.of(entries);
        }
    }
}
