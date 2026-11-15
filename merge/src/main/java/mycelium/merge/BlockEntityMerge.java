package mycelium.merge;

import mycelium.chunk.BlockPos;
import mycelium.nbt.ByteTag;
import mycelium.nbt.CompoundTag;
import mycelium.nbt.IntTag;
import mycelium.nbt.ListTag;
import mycelium.nbt.StringTag;
import mycelium.nbt.Tag;
import mycelium.report.Category;
import mycelium.report.ReportEntry;
import mycelium.report.Severity;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Merges the block entity of a block that is the same on both sides.
 *
 * <p>A block entity with an {@code Items} list is a container: its slots merge one by one, and
 * its other fields merge one by one too. Two sides changing the same slot is an item conflict,
 * recorded for the later rebase; the merge itself never adds or removes items beyond picking a
 * side. Fields that only count progress, such as burn time, go to the newer side unrecorded.
 * Every other block entity merges as one whole value, compared without its progress fields: two
 * vaults or beehives that differ only in a timer are the newer side's, unrecorded.
 */
final class BlockEntityMerge {

    private static final String ITEMS = "Items";
    private static final String SLOT = "Slot";
    private static final String COUNT = "count";
    private static final String LOOT = "LootTable";

    private BlockEntityMerge() {
    }

    /**
     * @param base the ancestor's block entity; null if it had none or there is no ancestor
     * @return the merged block entity, or null if the result has none
     */
    static CompoundTag merge(CompoundTag base, CompoundTag ours, CompoundTag theirs, BlockPos pos, Side newer,
                             MergeContext context, List<ReportEntry> entries) {
        if (Objects.equals(ours, theirs)) {
            return ours;
        }
        if (ours == null || theirs == null) {
            // Present on one side only: follow the 3-way rule on presence.
            return Values.pick(base, ours, theirs, newer, () -> entries.add(conflict(Category.BLOCK_ENTITY, pos,
                    base, ours, theirs, newer, context, null)));
        }
        SortedMap<Integer, Tag> oursSlots = slots(ours);
        SortedMap<Integer, Tag> theirsSlots = slots(theirs);
        SortedMap<Integer, Tag> baseSlots = base == null ? new TreeMap<>() : slots(base);
        boolean container = (ours.contains(ITEMS) || theirs.contains(ITEMS))
                && Objects.equals(ours.get("id"), theirs.get("id"))
                && oursSlots != null && theirsSlots != null && baseSlots != null;
        if (!container) {
            if (theirs.equals(base)) {
                return ours;
            }
            if (ours.equals(base)) {
                return theirs;
            }
            CompoundTag mine = context.rules().withoutProgress(ours);
            CompoundTag other = context.rules().withoutProgress(theirs);
            if (mine.equals(other)) {
                return newer == Side.OURS ? ours : theirs;   // they differ in progress fields only
            }
            CompoundTag was = base == null ? null : context.rules().withoutProgress(base);
            if (other.equals(was)) {
                return ours;
            }
            if (mine.equals(was)) {
                return theirs;
            }
            entries.add(conflict(Category.BLOCK_ENTITY, pos, base, ours, theirs, newer, context, null));
            return newer == Side.OURS ? ours : theirs;
        }

        if (base != null && base.contains(LOOT) && !ours.contains(LOOT) && !theirs.contains(LOOT)) {
            // Both sides opened a container that still had its loot table. Each rolled the loot for itself,
            // so the slots have no common past, and merging them would hand out both sides' loot.
            entries.add(conflict(Category.CONTAINER_SLOT, pos, base, ours, theirs, newer, context,
                    "both sides opened this loot container"));
            return newer == Side.OURS ? ours : theirs;
        }

        TreeSet<Integer> numbers = new TreeSet<>(oursSlots.keySet());
        numbers.addAll(theirsSlots.keySet());
        SortedMap<Integer, Tag> merged = new TreeMap<>();
        Set<CompoundTag> conflicting = new HashSet<>();     // the kinds of item a slot conflict is already recorded for
        for (int slot : numbers) {
            Tag was = baseSlots.get(slot);
            Tag mine = oursSlots.get(slot);
            Tag other = theirsSlots.get(slot);
            Tag value = Values.pick(was, mine, other, newer, () -> {
                entries.add(new ReportEntry(Severity.CONFLICT, Category.CONTAINER_SLOT, context.dimension(), pos,
                        text(was), text(mine), text(other), newer, "slot " + slot));
                for (Tag item : new Tag[] {was, mine, other}) {
                    if (item != null) {
                        conflicting.add(kind((CompoundTag) item));
                    }
                }
            });
            if (value != null) {
                merged.put(slot, value);
            }
        }
        balance(merged, baseSlots, newer == Side.OURS ? oursSlots : theirsSlots, newer == Side.OURS ? theirsSlots : oursSlots,
                conflicting, pos, newer, context, entries);
        List<Tag> items = new ArrayList<>(merged.values());

        CompoundTag rest = Values.mergeCompound(base == null ? null : base.without(ITEMS), ours.without(ITEMS),
                theirs.without(ITEMS), newer, key -> {
                    if (!context.rules().isProgressField(key)) {
                        entries.add(new ReportEntry(Severity.CONFLICT, Category.BLOCK_ENTITY, context.dimension(), pos,
                                base == null ? null : text(base.get(key)), text(ours.get(key)), text(theirs.get(key)),
                                newer, "field " + key));
                    }
                });
        // An unopened loot container has no Items list, and it stays that way if nobody opened it.
        boolean listed = !items.isEmpty() || (newer == Side.OURS ? ours : theirs).contains(ITEMS);
        return listed ? rest.with(ITEMS, ListTag.of(items)) : rest;
    }

    /**
     * Takes out what a move by both sides left twice. Merging slot by slot cannot see an item
     * move: a stack both sides moved, each to another slot, would be in both slots, and no slot
     * would conflict. So each kind of item is counted. The container should hold what the base
     * held plus what each side added or took. If it holds more, and the older side put that kind
     * into a slot where neither the base nor the newer side has it, the surplus is taken out of
     * such slots and recorded.
     *
     * <p>Nothing else is touched. A furnace that burned the same coal on both sides holds "more"
     * by this count too, but it is one side's furnace as a whole, not a move. And a kind of item
     * that a slot conflict was recorded for is left to that record and the later rebase.
     */
    private static void balance(SortedMap<Integer, Tag> merged, SortedMap<Integer, Tag> base, SortedMap<Integer, Tag> newerSlots,
                                SortedMap<Integer, Tag> olderSlots, Set<CompoundTag> recorded, BlockPos pos, Side newer,
                                MergeContext context, List<ReportEntry> entries) {
        Map<CompoundTag, Long> expected = new LinkedHashMap<>();
        count(newerSlots, 1, expected);
        count(olderSlots, 1, expected);
        count(base, -1, expected);
        Map<CompoundTag, Long> held = new HashMap<>();
        count(merged, 1, held);
        for (Map.Entry<CompoundTag, Long> kind : expected.entrySet()) {
            long surplus = held.getOrDefault(kind.getKey(), 0L) - Math.max(0, kind.getValue());
            if (surplus <= 0 || recorded.contains(kind.getKey())) {
                continue;   // nothing is there twice, or a conflict in one of its slots says so already
            }
            long left = surplus;
            for (int slot : new TreeSet<>(merged.keySet()).descendingSet()) {
                CompoundTag item = (CompoundTag) merged.get(slot);
                // Only a slot the older side filled with this kind, where neither the base nor the newer side has it.
                if (left <= 0 || !kind(item).equals(kind.getKey()) || !item.equals(olderSlots.get(slot))
                        || holds(newerSlots.get(slot), kind.getKey()) || holds(base.get(slot), kind.getKey())) {
                    continue;
                }
                long taken = Math.min(left, amount(item));
                left -= taken;
                if (taken == amount(item)) {
                    merged.remove(slot);
                } else {
                    merged.put(slot, item.with(COUNT, new IntTag((int) (amount(item) - taken))));
                }
            }
            if (left < surplus) {
                String what = kind.getKey().get("id") instanceof StringTag id ? id.value() : "items";
                entries.add(new ReportEntry(Severity.CONFLICT, Category.CONTAINER_SLOT, context.dimension(), pos, null,
                        null, null, newer, (surplus - left) + " " + what + " left out: both sides moved the same items"));
            }
        }
    }

    private static boolean holds(Tag item, CompoundTag kind) {
        return item != null && kind((CompoundTag) item).equals(kind);
    }

    /** Adds the amount of every item in some slots to a total per kind of item, times {@code sign}. */
    private static void count(SortedMap<Integer, Tag> slots, int sign, Map<CompoundTag, Long> totals) {
        for (Tag item : slots.values()) {
            totals.merge(kind((CompoundTag) item), sign * amount((CompoundTag) item), Long::sum);
        }
    }

    /** What an item is, whichever slot it is in and however many there are. */
    private static CompoundTag kind(CompoundTag item) {
        return item.without(SLOT, COUNT);
    }

    private static long amount(CompoundTag item) {
        return item.get(COUNT) instanceof IntTag count ? count.value() : 1;
    }

    /** The items by slot number, or null if this is not a plain slot list. An absent list has no slots. */
    private static SortedMap<Integer, Tag> slots(CompoundTag blockEntity) {
        SortedMap<Integer, Tag> slots = new TreeMap<>();
        Tag items = blockEntity.get(ITEMS);
        if (items == null) {
            return slots;
        }
        if (!(items instanceof ListTag list)) {
            return null;
        }
        for (Tag element : list) {
            if (!(element instanceof CompoundTag item) || !(item.get(SLOT) instanceof ByteTag slot)
                    || slots.put((int) slot.value(), item) != null) {
                return null;
            }
        }
        return slots;
    }

    private static ReportEntry conflict(Category category, BlockPos pos, Tag base, Tag ours, Tag theirs, Side newer,
                                        MergeContext context, String detail) {
        return new ReportEntry(Severity.CONFLICT, category, context.dimension(), pos, text(base), text(ours),
                text(theirs), newer, detail);
    }

    private static String text(Tag tag) {
        return tag == null ? null : tag.toString();
    }
}
