package mycelium.merge;

import mycelium.chunk.BlockPos;
import mycelium.chunk.ChunkFormatException;
import mycelium.chunk.ChunkPos;
import mycelium.chunk.Entities;
import mycelium.chunk.EntityIndex;
import mycelium.chunk.FlatEntity;
import mycelium.nbt.ByteTag;
import mycelium.nbt.CompoundTag;
import mycelium.nbt.IntArrayTag;
import mycelium.nbt.ListTag;
import mycelium.nbt.Tag;
import mycelium.report.Category;
import mycelium.report.ReportEntry;
import mycelium.report.Severity;
import mycelium.rules.Rules;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Merges the entities of one dimension as a set keyed by UUID, wherever they stand.
 *
 * <ul>
 * <li>Present on one side only and not in the base: kept, unless it is generated wildlife that the
 *     other side generated too; see below.
 * <li>Removed on one side, unchanged on the other: removed.
 * <li>Removed on one side, changed on the other: removed, and that is recorded. Whatever is
 *     loaded changes, so a change by itself says nothing; the side that no longer has the entity
 *     has what became of it, be it a drop, a frog or a full bucket. It is kept only if the other
 *     side changed its items, gave it a mark of a player or put it into something else.
 * <li>Changed on both sides: the newer side's entity. Mobs move all the time, so this is recorded
 *     as resolved, and as a conflict only if a field that holds items differs. The same goes for
 *     an entity both sides have and the base lacks: one UUID is one entity.
 * </ul>
 *
 * <p>A rider keeps the vehicle it has on the side that won it. If that vehicle did not survive,
 * the rider stands on its own. A leash or owner that points at an entity which did not survive
 * is reported.
 *
 * <p>An entity that a player's file holds, such as the boat the player left the game in, is one
 * more version of that entity: being taken along counts as a change. See {@link PlayerEntities}.
 *
 * <p>Terrain that both sides generated on their own holds the same generated animals and loot
 * carts twice, under different UUIDs. There, an animal, monster or unopened loot container that is
 * new on the older side and shows no mark of a player is left out for every such entity of the
 * same type that is new on the newer side.
 *
 * <p>The same goes for what both sides ended and began again under another UUID: a tadpole that
 * became a frog on both sides, an egg that hatched on both. See {@link Ending}.
 *
 * <p>Bees are entities while they fly and a list in their nest while they rest. Where both sides
 * changed a nest, the merged world has the newer side's nest, and so it has the newer side's
 * bees of that nest. See {@link Nest}.
 */
public final class EntityMerger {

    /**
     * @param entities the surviving entities with their rider links rebuilt
     * @param entries  what was resolved, what conflicted, and dangling references
     */
    public record Result(EntityIndex entities, List<ReportEntry> entries) {
    }

    /**
     * What the merge has to ask about the world around the entities.
     *
     * @param elsewhere  whether an entity that is not among those given is in the merged world all
     *                   the same, or is a player. Only asked about entities that a leash points at
     * @param held       whether a surviving entity that a player's file holds stays there. If not, it
     *                   goes back into the entity files, where it stands
     * @param unanchored whether the terrain at a chunk position has no finished chunk in the base, so
     *                   that each side generated it on its own
     */
    public record Surroundings(Predicate<UUID> elsewhere, Predicate<FlatEntity> held, Predicate<ChunkPos> unanchored,
                               List<Ending> endings, Function<BlockPos, Nest> nests) {

        /** Nothing but the entities given: no players, and every chunk has an ancestor. */
        public static final Surroundings NONE = new Surroundings(uuid -> false, entity -> false, pos -> false);

        /** Without endings and nests. */
        public Surroundings(Predicate<UUID> elsewhere, Predicate<FlatEntity> held, Predicate<ChunkPos> unanchored) {
            this(elsewhere, held, unanchored, List.of(), pos -> null);
        }
    }

    /**
     * Something of the base that neither side still has, and of which each side may have begun
     * another: a creature that turned into another kind, which is a new entity, or an egg that
     * hatched. For each, the older side's new creatures of the kinds it becomes are left out, as
     * many as the newer side has of its own and at most {@code count}, the nearest first. Only
     * creatures within {@link #TWIN_REACH} blocks count, and none with a mark of a player.
     *
     * <p>This goes by number and place, since nothing ties a frog to the tadpole it was. Where the
     * newer side got more out of an egg than the older side, and the older side has another new
     * creature of the kind close by, that one is left out in their stead.
     *
     * @param where   where it was in the base
     * @param what    its name, for the report
     * @param becomes the ids of what it becomes
     * @param count   how many it becomes at most
     */
    public record Ending(BlockPos where, String what, Set<String> becomes, int count) {
    }

    /**
     * A nest that both sides changed. The merged world has the newer side's.
     *
     * @param side   the side whose nest it is
     * @param inside how many creatures that side's nest holds
     */
    public record Nest(Side side, int inside) {
    }

    /** How far from where something ended a creature may be to count as what it became. */
    public static final int TWIN_REACH = 32;

    private EntityMerger() {
    }

    public static Result merge(EntityIndex base, EntityIndex ours, EntityIndex theirs, MergeContext context)
            throws ChunkFormatException {
        return merge(base, ours, theirs, context, Surroundings.NONE);
    }

    public static Result merge(EntityIndex base, EntityIndex ours, EntityIndex theirs, MergeContext context,
                               Surroundings around) throws ChunkFormatException {
        Rules rules = context.rules();
        SortedSet<UUID> all = new TreeSet<>(ours.entities().keySet());
        all.addAll(theirs.entities().keySet());
        SortedMap<UUID, FlatEntity> kept = new TreeMap<>();
        // Entities one side has that the base and the other side lack, and those of them in terrain
        // without an ancestor.
        SortedMap<UUID, Side> fresh = new TreeMap<>();
        SortedMap<UUID, Side> generated = new TreeMap<>();
        // Bees: how many of each nest fly on the side whose nest it is, and the other side's new ones.
        Rules.Hives hives = rules.hives();
        Map<BlockPos, Integer> flying = new HashMap<>();
        SortedMap<BlockPos, List<FlatEntity>> guests = new TreeMap<>();
        List<ReportEntry> entries = new ArrayList<>();
        for (UUID uuid : all) {
            FlatEntity was = base.get(uuid);
            FlatEntity mine = ours.get(uuid);
            FlatEntity other = theirs.get(uuid);
            FlatEntity winner;
            BlockPos oursHome = home(mine, hives);
            BlockPos theirsHome = home(other, hives);
            Nest oursNest = oursHome == null ? null : around.nests().apply(oursHome);
            Nest theirsNest = theirsHome == null ? null : around.nests().apply(theirsHome);
            if (oursNest != null && oursNest.side() == Side.OURS || theirsNest != null && theirsNest.side() == Side.THEIRS) {
                // A bee of a nest both sides changed, on the side whose nest it is: that side's bee.
                boolean oursWins = oursNest != null && oursNest.side() == Side.OURS;
                winner = oursWins ? mine : other;
                flying.merge(oursWins ? oursHome : theirsHome, 1, Integer::sum);
            } else if (oursNest != null && other == null || theirsNest != null && mine == null) {
                // Only the side that lost the nest has this bee in the air. If the base had it, the other
                // side saw it go into the nest or die. If not, it left the nest on this side, and it stays
                // only if the nest has room for it; that is decided once every bee was seen.
                winner = null;
                if (was == null) {
                    guests.computeIfAbsent(mine == null ? theirsHome : oursHome, key -> new ArrayList<>()).add(mine == null ? other : mine);
                }
            } else if (same(mine, other)) {
                winner = mine;
            } else if (same(mine, was)) {
                winner = other;
            } else if (same(other, was)) {
                winner = mine;
            } else if (mine == null || other == null) {
                // Removed on one side, changed on the other.
                FlatEntity changed = mine == null ? other : mine;
                Side remover = mine == null ? Side.OURS : Side.THEIRS;
                boolean gone = !changedByHand(was, changed, rules);
                winner = gone ? null : changed;
                entries.add(new ReportEntry(Severity.RESOLVED, Category.ENTITY, context.dimension(), blockPos(changed),
                        describe(was), mine == null ? null : describe(mine), other == null ? null : describe(other),
                        gone ? remover : remover.other(), uuid + ": removed by " + remover.label() + ", "
                        + (gone ? "only loaded by " + remover.other().label() : "changed by " + remover.other().label() + ", kept")));
            } else {
                Side newer = context.precedence().newerIn(mine.chunk());
                winner = newer == Side.OURS ? mine : other;
                boolean items = false;
                for (String field : rules.entityItemFields()) {
                    items |= !Objects.equals(mine.data().get(field), other.data().get(field));
                }
                entries.add(new ReportEntry(items ? Severity.CONFLICT : Severity.RESOLVED, Category.ENTITY,
                        context.dimension(), blockPos(winner), was == null ? null : describe(was), describe(mine),
                        describe(other), newer, uuid + (items ? ": items differ" : was == null ? ": new on both sides" : "")));
            }
            if (winner != null) {
                kept.put(uuid, winner);
                if (was == null && (mine == null || other == null)) {
                    fresh.put(uuid, mine == null ? Side.THEIRS : Side.OURS);
                    if (winner.vehicle() == null && around.unanchored().test(winner.chunk())) {
                        generated.put(uuid, mine == null ? Side.THEIRS : Side.OURS);
                    }
                }
            }
        }
        for (Map.Entry<BlockPos, List<FlatEntity>> nest : guests.entrySet()) {
            // The nest is not the side's that these bees are new on. They stay while it has room.
            Nest home = around.nests().apply(nest.getKey());
            int own = home.inside() + flying.getOrDefault(nest.getKey(), 0);
            int room = Math.max(0, Math.min(nest.getValue().size(), hives.capacity() - own));
            for (FlatEntity bee : nest.getValue().subList(0, room)) {
                kept.put(bee.uuid(), bee);
            }
            if (room < nest.getValue().size()) {
                entries.add(new ReportEntry(Severity.RESOLVED, Category.ENTITY, context.dimension(), nest.getKey(), null, null,
                        null, home.side(), (nest.getValue().size() - room) + " " + nest.getValue().get(0).id() + " of "
                        + home.side().other().label() + " left out: both sides changed their nest, and the nest of "
                        + home.side().label() + " has " + own + " of its own"));
            }
        }
        leaveOutGeneratedTwins(kept, generated, context, entries);
        leaveOutTwinsOfEndings(kept, fresh, around.endings(), context, entries);

        // Rebuild rider links from each survivor's own vehicle, so every rider appears exactly once.
        SortedMap<UUID, List<UUID>> riders = new TreeMap<>();
        for (FlatEntity entity : kept.values()) {
            if (entity.vehicle() != null && kept.containsKey(entity.vehicle()) && !ridesItself(entity, kept)) {
                riders.computeIfAbsent(entity.vehicle(), key -> new ArrayList<>()).add(entity.uuid());
            }
        }
        List<FlatEntity> linked = new ArrayList<>();
        for (FlatEntity entity : kept.values()) {
            // Seats keep the order the vehicle had them in; riders it did not know come after, by UUID.
            List<UUID> seats = new ArrayList<>(entity.passengers());
            List<UUID> actual = riders.getOrDefault(entity.uuid(), List.of());
            seats.retainAll(actual);
            for (UUID rider : actual) {
                if (!seats.contains(rider)) {
                    seats.add(rider);
                }
            }
            boolean rides = entity.vehicle() != null && riders.getOrDefault(entity.vehicle(), List.of()).contains(entity.uuid());
            UUID carrier = entity.carrier() != null && !rides && around.held().test(entity) ? entity.carrier() : null;
            linked.add(new FlatEntity(entity.uuid(), rides ? entity.vehicle() : null, seats, entity.data(), carrier));
            for (Map.Entry<String, UUID> reference : references(entity.data()).entrySet()) {
                if (!kept.containsKey(reference.getValue()) && !around.elsewhere().test(reference.getValue())) {
                    entries.add(new ReportEntry(Severity.RESOLVED, Category.ENTITY_REFERENCE, context.dimension(),
                            blockPos(entity), null, null, null, null,
                            entity.uuid() + " " + reference.getKey() + " points at " + reference.getValue() + ", which is not in the merged world"));
                }
            }
        }
        return new Result(EntityIndex.of(linked), entries);
    }

    /**
     * Where both sides generated the terrain, each has its own copy of what generation put there.
     * For every new entity of a type on the newer side that could be generated, one such entity of
     * that type on the older side is left out, lowest UUID first. The count is over the dimension,
     * because animals wander across chunk borders.
     */
    private static void leaveOutGeneratedTwins(SortedMap<UUID, FlatEntity> kept, SortedMap<UUID, Side> generated,
                                               MergeContext context, List<ReportEntry> entries) throws ChunkFormatException {
        SortedMap<String, Integer> pairs = new TreeMap<>();     // per type, the newer side's new entities
        SortedMap<String, List<UUID>> twins = new TreeMap<>();  // per type, the older side's unmarked ones
        SortedMap<String, Side> older = new TreeMap<>();
        for (Map.Entry<UUID, Side> entry : generated.entrySet()) {
            FlatEntity entity = kept.get(entry.getKey());
            Side newer = context.precedence().newerIn(entity.chunk());
            if (!looksGenerated(entity, context.rules())) {
                continue;
            }
            if (entry.getValue() == newer) {
                pairs.merge(entity.id(), 1, Integer::sum);
            } else {
                twins.computeIfAbsent(entity.id(), key -> new ArrayList<>()).add(entity.uuid());
                older.put(entity.id(), entry.getValue());
            }
        }
        for (Map.Entry<String, List<UUID>> type : twins.entrySet()) {
            int count = Math.min(type.getValue().size(), pairs.getOrDefault(type.getKey(), 0));
            if (count == 0) {
                continue;
            }
            type.getValue().subList(0, count).forEach(kept::remove);
            Side side = older.get(type.getKey());
            entries.add(new ReportEntry(Severity.RESOLVED, Category.ENTITY, context.dimension(), null, null, null, null,
                    side.other(), count + " " + type.getKey() + " of " + side.label() + " left out: both sides generated"
                    + " this terrain, and " + side.other().label() + " has as many of its own there"));
        }
    }

    /**
     * What both sides ended, each began again as a new entity. The newer side's stays, and for
     * each of them one of the older side's is left out: the nearest to where the thing ended.
     */
    private static void leaveOutTwinsOfEndings(SortedMap<UUID, FlatEntity> kept, SortedMap<UUID, Side> fresh,
                                               List<Ending> endings, MergeContext context, List<ReportEntry> entries)
            throws ChunkFormatException {
        Set<UUID> paired = new HashSet<>();
        for (Ending ending : endings) {
            Side newer = context.precedence().newerIn(new ChunkPos(ending.where().x() >> 4, ending.where().z() >> 4));
            List<FlatEntity> own = new ArrayList<>();
            List<FlatEntity> twins = new ArrayList<>();
            Map<UUID, Double> far = new HashMap<>();
            for (Map.Entry<UUID, Side> entry : fresh.entrySet()) {
                FlatEntity entity = kept.get(entry.getKey());
                if (entity == null || !ending.becomes().contains(entity.id()) || paired.contains(entity.uuid())
                        || distance(entity, ending.where()) > (double) TWIN_REACH * TWIN_REACH) {
                    continue;
                }
                far.put(entity.uuid(), distance(entity, ending.where()));
                if (entry.getValue() == newer) {
                    own.add(entity);
                } else if (!marked(entity, context.rules(), true) && entity.vehicle() == null && entity.passengers().isEmpty()
                        && entity.carrier() == null) {
                    twins.add(entity);
                }
            }
            int count = Math.min(ending.count(), Math.min(own.size(), twins.size()));
            if (count == 0) {
                continue;
            }
            // Nearest first; the lists are in UUID order, which settles a tie. The kinds may differ: what
            // hatched as a tadpole on both sides can be a frog on one of them by now.
            own.sort((a, b) -> Double.compare(far.get(a.uuid()), far.get(b.uuid())));
            twins.sort((a, b) -> Double.compare(far.get(a.uuid()), far.get(b.uuid())));
            own.subList(0, count).forEach(entity -> paired.add(entity.uuid()));
            SortedSet<String> kinds = new TreeSet<>();
            for (FlatEntity twin : twins.subList(0, count)) {
                kept.remove(twin.uuid());
                kinds.add(twin.id());
            }
            entries.add(new ReportEntry(Severity.RESOLVED, Category.ENTITY, context.dimension(), ending.where(), ending.what(),
                    null, null, newer, count + " " + String.join(", ", kinds) + " of " + newer.other().label() + " left out: "
                    + ending.what() + " is gone on both sides, and " + newer.label() + " has what came of it"));
        }
    }

    /** The square of the distance between an entity and a block. */
    private static double distance(FlatEntity entity, BlockPos pos) throws ChunkFormatException {
        double[] at = Entities.positionOf(entity.data());
        double dx = at[0] - (pos.x() + 0.5);
        double dy = at[1] - pos.y();
        double dz = at[2] - (pos.z() + 0.5);
        return dx * dx + dy * dy + dz * dz;
    }

    /** The nest an entity names as its home, or null if it is not one that lives in a nest or names none. */
    private static BlockPos home(FlatEntity entity, Rules.Hives hives) throws ChunkFormatException {
        if (entity == null || hives == null || !hives.entities().contains(entity.id())
                || !(entity.data().get(hives.home()) instanceof IntArrayTag pos) || pos.size() != 3) {
            return null;
        }
        return new BlockPos(pos.ints()[0], pos.ints()[1], pos.ints()[2]);
    }

    /**
     * Whether an entity carries a mark of a player.
     *
     * @param born whether the entity hatched or came of another creature, so that the marks the
     *             game gives at that moment do not count
     */
    private static boolean marked(FlatEntity entity, Rules rules, boolean born) {
        for (String field : rules.entityPlayerMarks()) {
            Tag value = entity.data().get(field);
            if (born && rules.entityBirthMarks().contains(field)) {
                continue;
            }
            if (value != null && !(value instanceof ByteTag flag && flag.value() == 0)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether an entity could be what world generation put there: an animal or monster, or a
     * container that still has its loot table. Not if it carries a mark of a player, holds items,
     * or is part of a stack of riders. A boat, a minecart or an item frame never counts: players
     * place those, and nothing in their data tells a placed one from a generated one.
     */
    private static boolean looksGenerated(FlatEntity entity, Rules rules) {
        if (entity.vehicle() != null || !entity.passengers().isEmpty() || entity.carrier() != null || marked(entity, rules, false)) {
            return false;
        }
        for (String field : rules.entityItemFields()) {
            Tag value = entity.data().get(field);
            if (value instanceof CompoundTag compound ? !compound.isEmpty() : value instanceof ListTag list ? !list.isEmpty()
                    : value != null) {
                return false;
            }
        }
        return entity.data().contains("Health") || entity.data().contains("LootTable");
    }

    /** Whether a version differs from the base in its items, a mark of a player, or what holds it. */
    private static boolean changedByHand(FlatEntity was, FlatEntity now, Rules rules) {
        if (!Objects.equals(was.vehicle(), now.vehicle()) || !Objects.equals(was.carrier(), now.carrier())) {
            return true;
        }
        for (String field : rules.entityItemFields()) {
            if (!Objects.equals(was.data().get(field), now.data().get(field))) {
                return true;
            }
        }
        for (String field : rules.entityPlayerMarks()) {
            if (!Objects.equals(was.data().get(field), now.data().get(field))) {
                return true;
            }
        }
        return false;
    }

    /** Whether two versions of an entity are the same: its data, what it rides, and whether a player holds it. */
    static boolean same(FlatEntity a, FlatEntity b) {
        if (a == null || b == null) {
            return a == b;
        }
        return a.data().equals(b.data()) && Objects.equals(a.vehicle(), b.vehicle())
                && Objects.equals(a.carrier(), b.carrier());
    }

    private static boolean ridesItself(FlatEntity entity, Map<UUID, FlatEntity> kept) {
        FlatEntity current = entity;
        for (int depth = 0; current.vehicle() != null && kept.containsKey(current.vehicle()); depth++) {
            current = kept.get(current.vehicle());
            if (current.uuid().equals(entity.uuid()) || depth > kept.size()) {
                return true;
            }
        }
        return false;
    }

    /** References to other entities: a leash held by an entity or a player. */
    private static Map<String, UUID> references(CompoundTag data) {
        Map<String, UUID> references = new TreeMap<>();
        if (data.get("leash") instanceof CompoundTag leash && leash.get("UUID") instanceof IntArrayTag holder
                && holder.size() == 4) {
            references.put("leash", Entities.toUuid(holder.ints()));
        }
        // An owner is usually a player, who is not an entity in the entity files; only report mob owners.
        return references;
    }

    private static BlockPos blockPos(FlatEntity entity) throws ChunkFormatException {
        double[] position = Entities.positionOf(entity.data());
        return new BlockPos((int) Math.floor(position[0]), (int) Math.floor(position[1]), (int) Math.floor(position[2]));
    }

    private static String describe(FlatEntity entity) throws ChunkFormatException {
        ChunkPos chunk = entity.chunk();
        Tag id = entity.data().get("id");
        return (id == null ? "entity" : id.toString().replace("\"", "")) + " in chunk " + chunk.x() + "," + chunk.z();
    }
}
