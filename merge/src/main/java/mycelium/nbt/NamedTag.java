package mycelium.nbt;

import java.util.Objects;

/** A root tag with the name it carries on disk. The game writes an empty name. */
public record NamedTag(String name, Tag tag) {

    public NamedTag {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(tag, "tag");
    }

    public static NamedTag of(Tag tag) {
        return new NamedTag("", tag);
    }
}
