package mycelium.nbt;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * A list of tags that all share one type, as binary NBT stores it.
 *
 * <p>Minecraft 26.3 also keeps lists of mixed types in this format. It writes them as a list of
 * compounds and wraps every element that is not a compound in {@code {"": element}}. A compound
 * that would itself look like such a wrapper is wrapped too. This class stores the list as it is
 * on disk; {@link #mixed} and {@link #logical} convert to and from the unwrapped view.
 *
 * <p>The element type an empty list declares is kept for writing but does not take part in
 * equality, because the game treats all empty lists alike. It writes them with type END.
 */
public final class ListTag implements Tag, Iterable<Tag> {

    private static final ListTag EMPTY = new ListTag(TagType.END, List.of());
    private static final String WRAPPER_KEY = "";

    private final TagType elementType;
    private final List<Tag> elements;

    private ListTag(TagType elementType, List<Tag> elements) {
        this.elementType = elementType;
        this.elements = elements;
    }

    /** The empty list as the game writes it, with element type END. */
    public static ListTag empty() {
        return EMPTY;
    }

    /**
     * A list of {@code elements}, which must all be of {@code elementType}. An empty list keeps
     * the declared type.
     */
    public static ListTag of(TagType elementType, List<? extends Tag> elements) {
        for (Tag element : elements) {
            if (element.type() != elementType) {
                throw new IllegalArgumentException(
                        "list of " + elementType + " cannot hold a " + element.type() + " element");
            }
        }
        if (elementType == TagType.END && !elements.isEmpty()) {
            throw new IllegalArgumentException("a list of END cannot have elements");
        }
        return new ListTag(elementType, List.copyOf(elements));
    }

    /** A list of same-typed elements; an empty list gets type END, as the game writes it. */
    public static ListTag of(List<? extends Tag> elements) {
        return elements.isEmpty() ? EMPTY : of(elements.get(0).type(), elements);
    }

    /**
     * A list that may mix types, encoded the way 26.3 does. Elements of one type are stored as
     * they are; otherwise the list becomes a list of compounds with wrapped elements.
     */
    public static ListTag mixed(List<? extends Tag> elements) {
        if (elements.isEmpty()) {
            return EMPTY;
        }
        TagType common = elements.get(0).type();
        for (Tag element : elements) {
            if (element.type() != common) {
                common = TagType.COMPOUND;
                break;
            }
        }
        if (common != TagType.COMPOUND) {
            return new ListTag(common, List.copyOf(elements));
        }
        List<Tag> stored = new ArrayList<>(elements.size());
        for (Tag element : elements) {
            boolean plainCompound = element instanceof CompoundTag compound && !isWrapper(compound);
            stored.add(plainCompound ? element : CompoundTag.of(WRAPPER_KEY, element));
        }
        return new ListTag(TagType.COMPOUND, List.copyOf(stored));
    }

    /** The element type declared on disk. END for an empty list written by the game. */
    public TagType elementType() {
        return elementType;
    }

    /** The elements as stored, wrappers included. Unmodifiable. */
    public List<Tag> elements() {
        return elements;
    }

    /** The elements with {@code {"": element}} wrappers removed, as the game sees them. */
    public List<Tag> logical() {
        if (elementType != TagType.COMPOUND) {
            return elements;
        }
        List<Tag> unwrapped = new ArrayList<>(elements.size());
        for (Tag element : elements) {
            unwrapped.add(unwrap(element));
        }
        return List.copyOf(unwrapped);
    }

    /** Removes one {@code {"": element}} wrapper if {@code element} is one. */
    public static Tag unwrap(Tag element) {
        return element instanceof CompoundTag compound && isWrapper(compound) ? compound.get(WRAPPER_KEY) : element;
    }

    private static boolean isWrapper(CompoundTag compound) {
        return compound.size() == 1 && compound.contains(WRAPPER_KEY);
    }

    public int size() {
        return elements.size();
    }

    public boolean isEmpty() {
        return elements.isEmpty();
    }

    public Tag get(int index) {
        return elements.get(index);
    }

    @Override
    public Iterator<Tag> iterator() {
        return elements.iterator();
    }

    @Override
    public TagType type() {
        return TagType.LIST;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ListTag that && elements.equals(that.elements);
    }

    @Override
    public int hashCode() {
        return elements.hashCode();
    }

    @Override
    public String toString() {
        return Snbt.format(this);
    }
}
