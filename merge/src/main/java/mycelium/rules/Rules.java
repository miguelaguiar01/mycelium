package mycelium.rules;

import mycelium.chunk.BlockPos;
import mycelium.chunk.BlockState;
import mycelium.json.Json;
import mycelium.merge.Side;
import mycelium.nbt.CompoundTag;
import mycelium.nbt.ListTag;
import mycelium.nbt.Tag;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.Predicate;

/**
 * The noise rules and multi-block unit definitions, as data.
 *
 * <p>A rules file names the blocks that join each rule:
 *
 * <pre>{
 *   "noise": [
 *     {"name": "growth", "type": "property", "result": "higher", "properties": ["age"], "blocks": [...]},
 *     {"name": "fire", "type": "transient", "blocks": [...], "empty": ["minecraft:air"]},
 *     {"name": "oxidation", "type": "stages", "chains": [["minecraft:copper_block", "minecraft:exposed_copper", ...]]},
 *     {"name": "growth", "type": "stages", "from": ["minecraft:air"], "chains": [[[leaves...], [logs...]]]}
 *   ],
 *   "units": [
 *     {"name": "door", "type": "vertical_pair", "shared": ["facing"], "blocks": [...]},
 *     {"name": "bed", "type": "bed", "blocks": [...]},
 *     {"name": "double_chest", "type": "double_chest", "blocks": [...]},
 *     {"name": "piston", "type": "piston", "blocks": [...], "head": "...", "moving": "..."}
 *   ]
 * }</pre>
 *
 * <p>Noise rule types:
 * <ul>
 * <li>{@code property}: both sides have the same block and differ only in the listed properties.
 *     {@code higher} takes the larger number for each, {@code newer} takes the newer side's value.
 * <li>{@code transient}: blocks the game puts where one of {@code empty} is and takes away again,
 *     or turns into each other back and forth. Both sides hold one of {@code blocks} or one of
 *     {@code empty}, and at least one holds one of {@code blocks}. The newer side wins. With
 *     {@code "result": "empty"} the rule needs a base, and where one side still has the base's
 *     block and the other one of {@code empty}, that one wins: a leaf that decayed. Where there
 *     is a base, it must hold one of the rule's blocks too, unless the rule says
 *     {@code "consumes": true}: fire takes the place of whatever burns.
 * <li>{@code stages}: blocks the game turns into each other in one direction only. A chain lists
 *     its stages in order; a stage is one block or a list of blocks that stand at the same stage.
 *     Both sides hold blocks of one chain at different stages, and the game brought both there
 *     from the base's block: the later stage wins. With {@code from}, the same holds where the
 *     base has one of those blocks and both sides have a block of the chain, as when two buds
 *     grew into the same air.
 * <li>{@code newer_only}: blocks of a plant that only stands as a whole, such as a chorus plant.
 *     Block by block, two shapes of it merge into one the game breaks. Where the newer side had
 *     the chunk in play, the plant is the newer side's: a block of this kind that the older side
 *     grew is not taken, and what the older side removed stays removed.
 * </ul>
 *
 * <p>These rules also say what the game does by itself to a block of the base: it changes only
 * properties a {@code property} rule lists, not to a value only a player sets and not below the
 * base's stage; or it swaps blocks of a {@code transient} rule; or it moves a block forward along
 * its chain. Where one side's block is such a change and the other side's is not, the other side
 * did something a player does, and its block is taken.
 *
 * <p>Unit types give the geometry, which was checked in the game (tools/mc/probe_units.py):
 * {@code vertical_pair} links {@code half=lower} to {@code half=upper} directly above;
 * {@code bed} links {@code part=foot} to {@code part=head} one step along {@code facing};
 * {@code double_chest} links {@code type=left} to {@code type=right} one step clockwise from
 * {@code facing}; {@code piston} links a base with {@code extended=true} to its head one step
 * along {@code facing}.
 *
 * <p>Some entries are about creatures. {@code entity_player_marks} lists the entity fields that
 * show a player's hand, and {@code entity_birth_marks} those of them that the game itself sets on
 * what hatches or comes of another creature. {@code entity_becomes} says which creature turns
 * into which as a new entity, {@code hatches} which block hatches into which creatures and how
 * many at most, and {@code hives} which creatures live in a block part of the time. The entity
 * merge uses the last three to have a creature once that both sides ended and began again under
 * another UUID.
 *
 * <p>Two more lists name NBT fields: {@code block_entity_progress} are block entity fields that
 * only count progress, such as a furnace's burn time, and {@code entity_item_fields} are entity
 * fields that hold items. A progress field is a path from the block entity's root: {@code a.b} is
 * field {@code b} of the compound {@code a}, and {@code a[].b} is field {@code b} of every
 * compound in the list {@code a}.
 *
 * <p>Every block and property a rules file names must exist in the block table. Rules loaded
 * later replace earlier ones for the same block and property.
 */
public final class Rules {

    private static final String VANILLA = "rules-26.3.json";

    /**
     * How a noise rule settled a block that both sides changed.
     *
     * @param state  the resulting state, in stored form
     * @param origin the side whose block entity and ticks go with it
     * @param rule   the rule's name
     */
    public record Noise(BlockState state, Side origin, String rule) {
    }

    /**
     * The other half that a unit member expects.
     *
     * @param rule    the unit rule's name
     * @param partner where the other half must be
     * @param accepts whether a block state at that position is the right other half
     * @param alone   the state the block takes if it has to stand without its other half, or null if
     *                it cannot: half a double chest becomes a single chest
     */
    public record UnitLink(String rule, BlockPos partner, Predicate<BlockState> accepts, BlockState alone) {
    }

    /**
     * @param higher       whether the larger number wins; otherwise the newer side's value does
     * @param playerValues values that only a player sets. A side that set one made a real change
     */
    private record PropertyRule(String name, boolean higher, Set<String> playerValues) {
    }

    /**
     * @param emptyWins whether one of {@code empty} beats one of {@code blocks}; otherwise the newer side wins
     * @param consumes  whether the blocks take the place of any block, as fire does of what it burns
     */
    private record SetRule(String name, Set<String> blocks, Set<String> empty, boolean emptyWins, boolean consumes) {
    }

    /**
     * A block's place in a chain of a {@code stages} rule.
     *
     * @param chain a number that the blocks of one chain share
     * @param index the stage, counted from 0
     * @param from  the blocks in whose place both sides may have grown a block of this chain
     */
    private record Stage(String rule, int chain, int index, Set<String> from) {
    }

    private record Unit(String name, String type, List<String> shared, Set<String> blocks, String head, String moving) {
    }

    private static final Map<String, int[]> DIRECTIONS = Map.of(
            "north", new int[] {0, 0, -1}, "south", new int[] {0, 0, 1}, "west", new int[] {-1, 0, 0},
            "east", new int[] {1, 0, 0}, "up", new int[] {0, 1, 0}, "down", new int[] {0, -1, 0});
    private static final Map<String, String> CLOCKWISE =
            Map.of("north", "east", "east", "south", "south", "west", "west", "north");
    private static final Map<String, String> OPPOSITE = Map.of(
            "north", "south", "south", "north", "west", "east", "east", "west", "up", "down", "down", "up");

    private final BlockTable table;
    private final Map<String, Map<String, PropertyRule>> propertyRules;
    private final List<SetRule> transientRules;
    private final Map<String, Stage> stages;     // by block
    private final Map<String, String> newerOnly; // rule name by block
    private final Map<String, Unit> units;       // by member block
    private final Map<String, Unit> pistonHeads;  // by head block
    private final Set<String> progressFields;
    private final List<String[]> progressPaths;   // the same fields, split at the dots, in sorted order
    private final Set<String> entityItemFields;
    private final Set<String> statTimers;
    private final Set<String> entityPlayerMarks;
    private final Set<String> entityBirthMarks;
    private final Map<String, Set<String>> entityBecomes;
    private final Map<String, Hatch> hatches;    // by block
    private final Hives hives;

    private Rules(Builder builder) {
        this.table = builder.table;
        Map<String, Map<String, PropertyRule>> copy = new HashMap<>();
        builder.propertyRules.forEach((block, rules) -> copy.put(block, Map.copyOf(rules)));
        this.propertyRules = Map.copyOf(copy);
        this.transientRules = List.copyOf(builder.transientRules);
        this.stages = Map.copyOf(builder.stages);
        this.newerOnly = Map.copyOf(builder.newerOnly);
        this.units = Map.copyOf(builder.units);
        this.pistonHeads = Map.copyOf(builder.pistonHeads);
        this.progressFields = Set.copyOf(builder.progressFields);
        this.progressPaths = builder.progressFields.stream().sorted().map(field -> field.split("\\.")).toList();
        this.entityItemFields = Set.copyOf(builder.entityItemFields);
        this.statTimers = Set.copyOf(builder.statTimers);
        this.entityPlayerMarks = Set.copyOf(builder.entityPlayerMarks);
        this.entityBirthMarks = Set.copyOf(builder.entityBirthMarks);
        this.entityBecomes = Map.copyOf(builder.entityBecomes);
        this.hatches = Map.copyOf(builder.hatches);
        this.hives = builder.hives;
    }

    /** The built-in rules for vanilla Minecraft 26.3. */
    public static Rules vanilla(BlockTable table) {
        return builder(table).build();
    }

    /** A builder that starts from the vanilla rules. */
    public static Builder builder(BlockTable table) {
        return new Builder(table).addRules(BlockTable.resource(VANILLA), VANILLA);
    }

    public BlockTable table() {
        return table;
    }

    /** Whether a top-level block entity field only counts progress, so differing values are not a conflict. */
    public boolean isProgressField(String field) {
        return progressFields.contains(field);
    }

    /** The block entity without its progress fields, for comparing everything else. */
    public CompoundTag withoutProgress(CompoundTag blockEntity) {
        CompoundTag stripped = blockEntity;
        for (String[] path : progressPaths) {
            stripped = without(stripped, path, 0);
        }
        return stripped;
    }

    private static CompoundTag without(CompoundTag compound, String[] path, int depth) {
        boolean list = path[depth].endsWith("[]");
        String key = list ? path[depth].substring(0, path[depth].length() - 2) : path[depth];
        Tag value = compound.get(key);
        if (value == null) {
            return compound;
        }
        if (depth == path.length - 1) {
            return compound.without(key);
        }
        if (!list && value instanceof CompoundTag child) {
            return compound.with(key, without(child, path, depth + 1));
        }
        if (list && value instanceof ListTag elements) {
            List<Tag> stripped = new ArrayList<>();
            for (Tag element : elements) {
                stripped.add(element instanceof CompoundTag child ? without(child, path, depth + 1) : element);
            }
            return compound.with(key, ListTag.of(elements.elementType(), stripped));
        }
        return compound;
    }

    /** The entity fields that hold items. */
    public Set<String> entityItemFields() {
        return entityItemFields;
    }

    /**
     * The statistics that restart at zero instead of counting up, each as {@code group/name}, such
     * as {@code minecraft:custom/minecraft:time_since_death}.
     */
    public Set<String> statTimers() {
        return statTimers;
    }

    /** The entity fields that show a player's hand, such as an owner or a name. */
    public Set<String> entityPlayerMarks() {
        return entityPlayerMarks;
    }

    /**
     * The marks of a player that the game itself gives to what hatches from an egg or comes of
     * another creature: a frog that was a tadpole never despawns. On such a creature they say
     * nothing about a player.
     */
    public Set<String> entityBirthMarks() {
        return entityBirthMarks;
    }

    /**
     * What a creature of this id can turn into, as a new entity with a new UUID: a tadpole into a
     * frog. Null if it does not.
     */
    public Set<String> entityBecomes(String id) {
        return entityBecomes.get(id);
    }

    /**
     * What hatches from a block.
     *
     * @param into          the ids of what comes out, in every form it may have taken since: a tadpole or a frog
     * @param count         the most one block gives
     * @param countProperty the block property that says how many this block gives, or null if none
     *                      does: a turtle egg block holds 1 to 4 eggs
     */
    public record Hatch(Set<String> into, int count, String countProperty) {

        /** How many a block in this state gives: its count property if it has one, else the most. */
        public int count(BlockState state) {
            try {
                return countProperty == null ? count : Math.min(count, Integer.parseInt(state.properties().get(countProperty)));
            } catch (NumberFormatException e) {
                return count;
            }
        }
    }

    /** What hatches from the block of this name, or null if nothing does. */
    public Hatch hatch(String block) {
        return hatches.get(block);
    }

    /**
     * Creatures that live in a block part of the time, as bees do in a nest. Inside, they are a
     * list in the block entity and have no UUID; outside, they are entities that name the block.
     *
     * @param entities  the ids of the creatures
     * @param home      the entity field that holds the block's position, three integers
     * @param occupants the block entity field that lists the creatures inside
     * @param capacity  how many one block holds
     */
    public record Hives(Set<String> entities, String home, String occupants, int capacity) {
    }

    /** The rule for creatures that live in a block, or null if there is none. */
    public Hives hives() {
        return hives;
    }

    /**
     * Applies the noise rules to a block that both sides changed to different states.
     *
     * @param base the common ancestor's state, or null if there is none
     * @return the outcome, or null if no rule applies and the change is a true conflict
     */
    public Noise noise(BlockState base, BlockState ours, BlockState theirs, Side newer) {
        BlockState a = table.expand(ours);
        BlockState b = table.expand(theirs);
        BlockState was = base == null ? null : table.expand(base);
        if (a.name().equals(b.name())) {
            Noise settled = propertyNoise(was, a, b, newer);
            if (settled != null) {
                return settled;
            }
        }
        // What the game did by itself on one side loses to what only a player does on the other.
        String byOurs = was == null ? null : gameMade(was, a);
        String byTheirs = was == null ? null : gameMade(was, b);
        if (byOurs != null && byTheirs == null) {
            return new Noise(theirs, Side.THEIRS, byOurs);
        }
        if (byTheirs != null && byOurs == null) {
            return new Noise(ours, Side.OURS, byTheirs);
        }
        // A plant that is taken whole from the newer side: the older side's block of it never stands.
        String whole = newerOnly.get((newer == Side.OURS ? b : a).name());
        if (was != null && whole != null) {
            return new Noise(newer == Side.OURS ? ours : theirs, newer, whole);
        }
        // Two blocks the game swaps. With a base, the game must have made both from it, or the rule's blocks
        // must be of the kind that eats what was there: two players who each put grass or dirt where a stone
        // was have a conflict, and a plank that burns on one side and is burnt out on the other has none.
        SetRule swapped = transientRule(a, b);
        if (swapped != null && (was == null ? !swapped.emptyWins() : byOurs != null || swapped.consumes())) {
            Side winner = newer;
            if (swapped.emptyWins()) {
                // The base's leaf decayed on one side and is still there on the other: it is gone, whichever
                // side is newer, for it has lost its tree. Without a base nobody knows which of the two it is.
                boolean oursGone = swapped.empty().contains(a.name());
                if (oursGone != swapped.empty().contains(b.name()) && (oursGone ? b : a).name().equals(was.name())) {
                    winner = oursGone ? Side.OURS : Side.THEIRS;
                }
            }
            return new Noise(winner == Side.OURS ? ours : theirs, winner, swapped.name());
        }
        // Two stages of one chain: the later one. With a base, only if the game brought both sides there, or
        // if both stand where the game puts blocks of this chain.
        Stage mine = stages.get(a.name());
        Stage other = stages.get(b.name());
        if (mine != null && other != null && mine.chain() == other.chain() && mine.index() != other.index()
                && (was == null || byOurs != null || mine.from().contains(was.name()))) {
            return mine.index() > other.index() ? new Noise(ours, Side.OURS, mine.rule()) : new Noise(theirs, Side.THEIRS, other.rule());
        }
        return null;
    }

    /**
     * The name of the rule that takes this block from the newer side only, or null. A block of
     * such a rule that only the older side grew is left out where the newer side had the chunk in
     * play; see {@code newer_only} above.
     */
    public String newerOnly(BlockState state) {
        return newerOnly.get(state.name());
    }

    /** Whether any rule takes blocks from the newer side only. */
    public boolean hasNewerOnly() {
        return !newerOnly.isEmpty();
    }

    /**
     * The transient rule that lets the game turn one of these blocks into the other, or null. A
     * block with a value only a player sets is nobody's but the player's: a leaf placed by hand
     * neither grew nor decays.
     */
    private SetRule transientRule(BlockState first, BlockState second) {
        if (setByPlayer(first) || setByPlayer(second)) {
            return null;
        }
        for (SetRule rule : transientRules) {
            boolean firstIn = rule.blocks().contains(first.name());
            boolean secondIn = rule.blocks().contains(second.name());
            if ((firstIn || secondIn) && (firstIn || rule.empty().contains(first.name()))
                    && (secondIn || rule.empty().contains(second.name()))) {
                return rule;
            }
        }
        return null;
    }

    private boolean setByPlayer(BlockState state) {
        Map<String, PropertyRule> rules = propertyRules.get(state.name());
        if (rules != null) {
            for (Map.Entry<String, String> property : state.properties().entrySet()) {
                PropertyRule rule = rules.get(property.getKey());
                if (rule != null && rule.playerValues().contains(property.getValue())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The name of the rule by which the game alone turns the base's block into {@code state}, or
     * null if a player must have had a hand in it. Both states are expanded and differ.
     */
    private String gameMade(BlockState base, BlockState state) {
        if (state.name().equals(base.name())) {
            String rule = onlyNoise(base, state);
            if (rule != null) {
                return rule;
            }
        }
        SetRule swapped = transientRule(base, state);
        if (swapped != null) {
            return swapped.name();
        }
        Stage to = stages.get(state.name());
        if (to == null || state.name().equals(base.name())) {
            return null;
        }
        // A block that appears where the chain's {@code from} is does not count: a player places those too.
        Stage from = stages.get(base.name());
        return from != null && from.chain() == to.chain() && from.index() < to.index() && sharedOnlyNoise(base, state)
                ? to.rule() : null;
    }

    /**
     * The rule's name if a state is the base's block with only properties changed the way the game
     * changes them: none to a value only a player sets, and no stage below the base's.
     */
    private String onlyNoise(BlockState base, BlockState state) {
        if (!state.properties().keySet().equals(base.properties().keySet())) {
            return null;
        }
        Map<String, PropertyRule> rules = propertyRules.getOrDefault(state.name(), Map.of());
        String name = null;
        for (Map.Entry<String, String> property : state.properties().entrySet()) {
            String was = base.properties().get(property.getKey());
            if (property.getValue().equals(was)) {
                continue;
            }
            PropertyRule rule = rules.get(property.getKey());
            if (rule == null || rule.playerValues().contains(property.getValue())
                    || rule.higher() && below(property.getValue(), was)) {
                return null;
            }
            name = name == null ? rule.name() : name;
        }
        return name;
    }

    /** Whether the properties two blocks of a chain have in common are the same, give or take noise. */
    private boolean sharedOnlyNoise(BlockState base, BlockState state) {
        Map<String, PropertyRule> rules = propertyRules.getOrDefault(state.name(), Map.of());
        for (Map.Entry<String, String> property : state.properties().entrySet()) {
            String was = base.properties().get(property.getKey());
            if (was != null && !was.equals(property.getValue()) && !rules.containsKey(property.getKey())) {
                return false;
            }
        }
        return true;
    }

    private static boolean below(String value, String start) {
        try {
            return Integer.parseInt(value) < Integer.parseInt(start);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * @param base the base's state if it is the same block, to tell growth from a reset; null if there is none
     */
    private Noise propertyNoise(BlockState base, BlockState a, BlockState b, Side newer) {
        Map<String, PropertyRule> rules = propertyRules.get(a.name());
        if (rules == null || !a.properties().keySet().equals(b.properties().keySet())) {
            return null;
        }
        SortedMap<String, String> result = new TreeMap<>((newer == Side.OURS ? a : b).properties());
        String name = null;
        for (Map.Entry<String, String> property : a.properties().entrySet()) {
            String mine = property.getValue();
            String theirs = b.properties().get(property.getKey());
            if (mine.equals(theirs)) {
                continue;
            }
            PropertyRule rule = rules.get(property.getKey());
            if (rule == null) {
                return null;   // a difference no rule covers is a real edit
            }
            name = name == null ? rule.name() : name;
            String was = base != null && base.name().equals(a.name()) ? base.properties().get(property.getKey()) : null;
            // A value only a player sets, such as a fluid's source level: the side that set it wins.
            boolean setByOurs = rule.playerValues().contains(mine) && !mine.equals(was);
            boolean setByTheirs = rule.playerValues().contains(theirs) && !theirs.equals(was);
            if (setByOurs != setByTheirs) {
                result.put(property.getKey(), setByOurs ? mine : theirs);
            } else if (rule.higher()) {
                try {
                    int x = Integer.parseInt(mine);
                    int y = Integer.parseInt(theirs);
                    // Growth only goes up. A side below the base's stage harvested or replanted, and the other
                    // side growing on must not undo that.
                    Integer start = was == null ? null : Integer.valueOf(was);
                    int value = start != null && x < start && y >= start ? x
                            : start != null && y < start && x >= start ? y : Math.max(x, y);
                    result.put(property.getKey(), Integer.toString(value));
                } catch (NumberFormatException e) {
                    // Not a number: keep the newer side's value.
                }
            }
        }
        if (name == null) {
            return null;
        }
        BlockState state = new BlockState(a.name(), result);
        Side origin = state.equals(a) ? Side.OURS : state.equals(b) ? Side.THEIRS : newer;
        return new Noise(table.canonical(state), origin, name);
    }

    /**
     * The other half the block at {@code pos} needs, or null if the state is not part of a unit.
     */
    public UnitLink unit(BlockState state, BlockPos pos) {
        BlockState full = table.expand(state);
        Unit unit = units.get(full.name());
        if (unit == null) {
            Unit piston = pistonHeads.get(full.name());
            String facing = full.properties().get("facing");
            if (piston == null || facing == null || !DIRECTIONS.containsKey(facing)) {
                return null;
            }
            return link(piston, step(pos, OPPOSITE.get(facing)), other -> isMoving(piston, other)
                    || piston.blocks().contains(other.name()) && has(other, "extended", "true") && has(other, "facing", facing));
        }
        switch (unit.type()) {
            case "vertical_pair" -> {
                String half = full.properties().get("half");
                if (!"lower".equals(half) && !"upper".equals(half)) {
                    return null;
                }
                boolean lower = half.equals("lower");
                return link(unit, step(pos, lower ? "up" : "down"), other -> other.name().equals(full.name())
                        && has(other, "half", lower ? "upper" : "lower") && sameShared(unit, full, other));
            }
            case "bed" -> {
                String part = full.properties().get("part");
                String facing = full.properties().get("facing");
                if (facing == null || !CLOCKWISE.containsKey(facing) || !"foot".equals(part) && !"head".equals(part)) {
                    return null;
                }
                boolean foot = part.equals("foot");
                return link(unit, step(pos, foot ? facing : OPPOSITE.get(facing)), other -> other.name().equals(full.name())
                        && has(other, "part", foot ? "head" : "foot") && has(other, "facing", facing));
            }
            case "double_chest" -> {
                String type = full.properties().get("type");
                String facing = full.properties().get("facing");
                if (facing == null || !CLOCKWISE.containsKey(facing) || !"left".equals(type) && !"right".equals(type)) {
                    return null;
                }
                boolean left = type.equals("left");
                String toward = left ? CLOCKWISE.get(facing) : OPPOSITE.get(CLOCKWISE.get(facing));
                // Half a double chest can stand alone as a single chest, with what it holds.
                SortedMap<String, String> single = new TreeMap<>(full.properties());
                single.put("type", "single");
                UnitLink link = link(unit, step(pos, toward), other -> unit.blocks().contains(other.name())
                        && has(other, "type", left ? "right" : "left") && has(other, "facing", facing));
                return new UnitLink(link.rule(), link.partner(), link.accepts(), table.canonical(new BlockState(full.name(), single)));
            }
            case "piston" -> {
                String facing = full.properties().get("facing");
                if (!"true".equals(full.properties().get("extended")) || facing == null || !DIRECTIONS.containsKey(facing)) {
                    return null;
                }
                return link(unit, step(pos, facing), other -> isMoving(unit, other)
                        || other.name().equals(unit.head()) && has(other, "facing", facing));
            }
            default -> {
                return null;
            }
        }
    }

    private UnitLink link(Unit unit, BlockPos partner, Predicate<BlockState> accepts) {
        return new UnitLink(unit.name(), partner, other -> other != null && accepts.test(table.expand(other)), null);
    }

    private static boolean isMoving(Unit unit, BlockState other) {
        return other.name().equals(unit.moving());
    }

    private static boolean has(BlockState state, String property, String value) {
        return value.equals(state.properties().get(property));
    }

    private static boolean sameShared(Unit unit, BlockState a, BlockState b) {
        for (String property : unit.shared()) {
            if (!java.util.Objects.equals(a.properties().get(property), b.properties().get(property))) {
                return false;
            }
        }
        return true;
    }

    private static BlockPos step(BlockPos pos, String direction) {
        int[] d = DIRECTIONS.get(direction);
        return new BlockPos(pos.x() + d[0], pos.y() + d[1], pos.z() + d[2]);
    }

    public static final class Builder {

        private final BlockTable table;
        private final Map<String, Map<String, PropertyRule>> propertyRules = new HashMap<>();
        private final List<SetRule> transientRules = new ArrayList<>();
        private final Map<String, Stage> stages = new HashMap<>();
        private final Map<String, String> newerOnly = new HashMap<>();
        private int chains;
        private final Map<String, Unit> units = new HashMap<>();
        private final Map<String, Unit> pistonHeads = new HashMap<>();
        private final Set<String> progressFields = new java.util.HashSet<>();
        private final Set<String> entityItemFields = new java.util.HashSet<>();
        private final Set<String> statTimers = new java.util.HashSet<>();
        private final Set<String> entityPlayerMarks = new java.util.HashSet<>();
        private final Set<String> entityBirthMarks = new java.util.HashSet<>();
        private final Map<String, Set<String>> entityBecomes = new HashMap<>();
        private final Map<String, Hatch> hatches = new HashMap<>();
        private Hives hives;

        private Builder(BlockTable table) {
            this.table = table;
        }

        /**
         * Adds a rules file.
         *
         * @param source a name for error messages
         * @throws IllegalArgumentException if the file is malformed or names a block or property the
         *                                  block table does not have
         */
        public Builder addRules(String json, String source) {
            Map<String, Object> root = Json.object(Json.parse(json), source);
            if (root.get("noise") != null) {
                for (Object element : Json.array(root.get("noise"), source + " noise")) {
                    addNoise(Json.object(element, source + " noise rule"), source);
                }
            }
            if (root.get("units") != null) {
                for (Object element : Json.array(root.get("units"), source + " units")) {
                    addUnit(Json.object(element, source + " unit rule"), source);
                }
            }
            progressFields.addAll(Json.strings(root.get("block_entity_progress"), source + " block_entity_progress"));
            entityItemFields.addAll(Json.strings(root.get("entity_item_fields"), source + " entity_item_fields"));
            statTimers.addAll(Json.strings(root.get("stat_timers"), source + " stat_timers"));
            entityPlayerMarks.addAll(Json.strings(root.get("entity_player_marks"), source + " entity_player_marks"));
            entityBirthMarks.addAll(Json.strings(root.get("entity_birth_marks"), source + " entity_birth_marks"));
            if (root.get("entity_becomes") != null) {
                Json.object(root.get("entity_becomes"), source + " entity_becomes").forEach((id, into) ->
                        entityBecomes.put(id, Set.copyOf(Json.strings(into, source + " entity_becomes " + id))));
            }
            if (root.get("hatches") != null) {
                for (Object element : Json.array(root.get("hatches"), source + " hatches")) {
                    Map<String, Object> hatch = Json.object(element, source + " hatch");
                    String where = source + " hatch";
                    String block = known(Json.string(hatch.get("block"), where + " block"), where);
                    String property = hatch.get("count_property") == null ? null
                            : Json.string(hatch.get("count_property"), where + " count_property");
                    if (property != null && !table.hasProperty(block, property)) {
                        throw new IllegalArgumentException(where + ": " + block + " has no property " + property);
                    }
                    hatches.put(block, new Hatch(Set.copyOf(Json.strings(hatch.get("into"), where + " into")),
                            number(hatch.get("count"), where + " count"), property));
                }
            }
            if (root.get("hives") != null) {
                Map<String, Object> rule = Json.object(root.get("hives"), source + " hives");
                String where = source + " hives";
                hives = new Hives(Set.copyOf(Json.strings(rule.get("entities"), where + " entities")),
                        Json.string(rule.get("home"), where + " home"), Json.string(rule.get("occupants"), where + " occupants"),
                        number(rule.get("capacity"), where + " capacity"));
            }
            return this;
        }

        private static int number(Object value, String what) {
            if (value instanceof Number number && number.doubleValue() == number.intValue() && number.intValue() > 0) {
                return number.intValue();
            }
            throw new IllegalArgumentException(what + " must be a whole number above 0");
        }

        private void addNoise(Map<String, Object> rule, String source) {
            String name = Json.string(rule.get("name"), source + " rule name");
            String where = source + " rule " + name;
            String type = Json.string(rule.get("type"), where + " type");
            Set<String> blocks = type.equals("stages") ? Set.of() : blocks(rule.get("blocks"), where);
            switch (type) {
                case "property" -> {
                    String result = Json.string(rule.get("result"), where + " result");
                    if (!result.equals("higher") && !result.equals("newer")) {
                        throw new IllegalArgumentException(where + ": result must be higher or newer, not " + result);
                    }
                    List<String> properties = Json.strings(rule.get("properties"), where + " properties");
                    for (String property : properties) {
                        if (blocks.stream().noneMatch(block -> table.hasProperty(block, property))) {
                            throw new IllegalArgumentException(where + ": no listed block has property " + property);
                        }
                        for (String block : blocks) {
                            if (table.hasProperty(block, property)) {
                                propertyRules.computeIfAbsent(block, key -> new HashMap<>())
                                        .put(property, new PropertyRule(name, result.equals("higher"),
                                                Set.copyOf(Json.strings(rule.get("player_values"), where + " player_values"))));
                            }
                        }
                    }
                }
                case "transient" -> {
                    String result = rule.get("result") == null ? "newer" : Json.string(rule.get("result"), where + " result");
                    if (!result.equals("newer") && !result.equals("empty")) {
                        throw new IllegalArgumentException(where + ": result must be newer or empty, not " + result);
                    }
                    transientRules.add(new SetRule(name, blocks, blocks(rule.get("empty"), where), result.equals("empty"),
                            Boolean.TRUE.equals(rule.get("consumes"))));
                }
                case "stages" -> addStages(name, rule, where);
                case "newer_only" -> blocks.forEach(block -> newerOnly.put(block, name));
                default -> throw new IllegalArgumentException(where + ": unknown noise rule type " + type);
            }
        }

        private void addStages(String name, Map<String, Object> rule, String where) {
            Set<String> from = rule.get("from") == null ? Set.of() : blocks(rule.get("from"), where + " from");
            List<Object> listed = Json.array(rule.get("chains"), where + " chains");
            if (listed.isEmpty()) {
                throw new IllegalArgumentException(where + ": no chains listed");
            }
            for (Object chain : listed) {
                int index = 0;
                for (Object stage : Json.array(chain, where + " chain")) {
                    // A stage is one block, or several that stand at the same stage.
                    Set<String> members = blocks(stage instanceof String ? List.of(stage) : stage, where + " chain");
                    for (String block : members) {
                        stages.put(block, new Stage(name, chains, index, from));
                    }
                    index++;
                }
                if (index == 0) {
                    throw new IllegalArgumentException(where + ": a chain has no blocks");
                }
                chains++;
            }
        }

        private void addUnit(Map<String, Object> rule, String source) {
            String name = Json.string(rule.get("name"), source + " unit name");
            String where = source + " unit " + name;
            String type = Json.string(rule.get("type"), where + " type");
            Set<String> blocks = blocks(rule.get("blocks"), where);
            List<String> shared = Json.strings(rule.get("shared"), where + " shared");
            String link = switch (type) {
                case "vertical_pair" -> "half";
                case "bed" -> "part";
                case "double_chest" -> "type";
                case "piston" -> "extended";
                default -> throw new IllegalArgumentException(where + ": unknown unit type " + type);
            };
            for (String block : blocks) {
                if (!table.hasProperty(block, link)) {
                    throw new IllegalArgumentException(where + ": " + block + " has no property " + link);
                }
                // These find the other half by the direction the block faces.
                if (!type.equals("vertical_pair") && !table.hasProperty(block, "facing")) {
                    throw new IllegalArgumentException(where + ": " + block + " has no property facing");
                }
            }
            for (String property : shared) {
                if (blocks.stream().noneMatch(block -> table.hasProperty(block, property))) {
                    throw new IllegalArgumentException(where + ": no listed block has property " + property);
                }
            }
            String head = null;
            String moving = null;
            if (type.equals("piston")) {
                head = known(Json.string(rule.get("head"), where + " head"), where);
                moving = rule.get("moving") == null ? null : known(Json.string(rule.get("moving"), where), where);
            }
            Unit unit = new Unit(name, type, List.copyOf(shared), blocks, head, moving);
            for (String block : blocks) {
                units.put(block, unit);
            }
            if (head != null) {
                pistonHeads.put(head, unit);
            }
        }

        private Set<String> blocks(Object value, String where) {
            List<String> names = Json.strings(value, where + " blocks");
            if (names.isEmpty()) {
                throw new IllegalArgumentException(where + ": no blocks listed");
            }
            for (String name : names) {
                known(name, where);
            }
            return Set.copyOf(names);
        }

        private String known(String block, String where) {
            if (!table.knows(block)) {
                throw new IllegalArgumentException(where + ": block " + block + " is in no loaded block report");
            }
            return block;
        }

        public Rules build() {
            return new Rules(this);
        }
    }
}
