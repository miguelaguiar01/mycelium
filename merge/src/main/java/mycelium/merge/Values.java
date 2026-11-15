package mycelium.merge;

import mycelium.nbt.CompoundTag;
import mycelium.nbt.Tag;
import java.util.Objects;
import java.util.TreeSet;
import java.util.function.Consumer;

/** The whole-value 3-way rule: unchanged on one side, take the other; changed on both, the newer side wins. */
final class Values {

    private Values() {
    }

    /**
     * Picks between three versions of a value. Any of them may be null, meaning absent.
     *
     * @param onConflict run when both sides changed the value in different ways
     */
    static <T> T pick(T base, T ours, T theirs, Side newer, Runnable onConflict) {
        if (Objects.equals(ours, theirs)) {
            return ours;
        }
        if (Objects.equals(ours, base)) {
            return theirs;
        }
        if (Objects.equals(theirs, base)) {
            return ours;
        }
        onConflict.run();
        return newer == Side.OURS ? ours : theirs;
    }

    /**
     * Merges two compounds key by key with {@link #pick}. A null compound has no keys. Keys come
     * out sorted.
     *
     * @param onConflict receives each key both sides changed differently
     */
    static CompoundTag mergeCompound(CompoundTag base, CompoundTag ours, CompoundTag theirs, Side newer,
                                     Consumer<String> onConflict) {
        CompoundTag o = base == null ? CompoundTag.empty() : base;
        CompoundTag a = ours == null ? CompoundTag.empty() : ours;
        CompoundTag b = theirs == null ? CompoundTag.empty() : theirs;
        if (a.equals(b)) {
            return a;
        }
        TreeSet<String> keys = new TreeSet<>(a.keys());
        keys.addAll(b.keys());
        CompoundTag.Builder merged = CompoundTag.builder();
        for (String key : keys) {
            Tag value = pick(o.get(key), a.get(key), b.get(key), newer, () -> onConflict.accept(key));
            merged.putIfPresent(key, value);
        }
        return merged.build();
    }
}
