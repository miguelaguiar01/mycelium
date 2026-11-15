package mycelium.nbt;

import java.util.Objects;

public record StringTag(String value) implements Tag {

    public StringTag {
        Objects.requireNonNull(value, "value");
    }

    @Override
    public TagType type() {
        return TagType.STRING;
    }

    @Override
    public String toString() {
        return Snbt.format(this);
    }
}
