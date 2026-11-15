package mycelium.rules;

import mycelium.chunk.BlockState;
import mycelium.json.Json;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * What is known about each block: its properties, their allowed values and its default state.
 *
 * <p>Minecraft 26.3 stores a block's default state without properties, so nothing can read a
 * property of such a state from the world file alone. This table fills the gap. It is loaded
 * from block reports in the format of the {@code blocks.json} that the game's data generator
 * writes: the built-in one for vanilla 26.3, plus any reports given for mods.
 *
 * <p>A block that is in no loaded report is unknown. Its states are left as they are.
 */
public final class BlockTable {

    private static final String VANILLA = "blocks-26.3.json";

    /** One block of a report. */
    public record Block(String name, SortedMap<String, List<String>> properties, SortedMap<String, String> defaults) {
    }

    private final Map<String, Block> blocks;

    private BlockTable(Map<String, Block> blocks) {
        this.blocks = blocks;
    }

    /** The built-in table for vanilla Minecraft 26.3. */
    public static BlockTable vanilla() {
        return builder().build();
    }

    /** A builder that starts from the vanilla table. */
    public static Builder builder() {
        Builder builder = new Builder();
        builder.addReport(resource(VANILLA), VANILLA);
        return builder;
    }

    static String resource(String name) {
        try (InputStream in = BlockTable.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("missing resource " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public boolean knows(String block) {
        return blocks.containsKey(block);
    }

    /** The block's entry, or null if no report has it. */
    public Block get(String block) {
        return blocks.get(block);
    }

    /** Whether the block exists and has the property. */
    public boolean hasProperty(String block, String property) {
        Block known = blocks.get(block);
        return known != null && known.properties().containsKey(property);
    }

    /**
     * The state with every property spelled out. A default state of a known block gets the
     * block's default values. Unknown blocks and states that already list properties are
     * returned unchanged.
     */
    public BlockState expand(BlockState state) {
        if (!state.isDefault()) {
            return state;
        }
        Block known = blocks.get(state.name());
        return known == null || known.defaults().isEmpty() ? state : new BlockState(state.name(), known.defaults());
    }

    /** The state as the game stores it: the bare name if it is the block's default state. */
    public BlockState canonical(BlockState state) {
        if (state.isDefault()) {
            return state;
        }
        Block known = blocks.get(state.name());
        return known != null && known.defaults().equals(state.properties()) ? BlockState.of(state.name()) : state;
    }

    public static final class Builder {

        private final Map<String, Block> blocks = new HashMap<>();

        private Builder() {
        }

        /**
         * Adds a block report: {@code {"<block>": {"properties": {...}, "states": [{"default": true,
         * "properties": {...}}, ...]}}}. A block already present is replaced.
         *
         * @param source a name for error messages
         * @throws IllegalArgumentException if the text is not a block report
         */
        public Builder addReport(String json, String source) {
            for (Map.Entry<String, Object> entry : Json.object(Json.parse(json), source).entrySet()) {
                String name = entry.getKey();
                String where = source + ": " + name;
                Map<String, Object> block = Json.object(entry.getValue(), where);
                SortedMap<String, List<String>> properties = new TreeMap<>();
                if (block.get("properties") != null) {
                    for (Map.Entry<String, Object> property : Json.object(block.get("properties"), where).entrySet()) {
                        properties.put(property.getKey(), List.copyOf(Json.strings(property.getValue(), where)));
                    }
                }
                SortedMap<String, String> defaults = null;
                for (Object element : Json.array(block.get("states"), where + " states")) {
                    Map<String, Object> state = Json.object(element, where);
                    if (Boolean.TRUE.equals(state.get("default"))) {
                        defaults = new TreeMap<>();
                        if (state.get("properties") != null) {
                            for (Map.Entry<String, Object> value : Json.object(state.get("properties"), where).entrySet()) {
                                defaults.put(value.getKey(), Json.string(value.getValue(), where));
                            }
                        }
                    }
                }
                if (defaults == null) {
                    throw new IllegalArgumentException(where + " has no default state");
                }
                if (!defaults.keySet().equals(properties.keySet())) {
                    throw new IllegalArgumentException(where + ": the default state does not list every property");
                }
                blocks.put(name, new Block(name, Collections.unmodifiableSortedMap(properties),
                        Collections.unmodifiableSortedMap(defaults)));
            }
            return this;
        }

        public BlockTable build() {
            return new BlockTable(Map.copyOf(blocks));
        }
    }
}
