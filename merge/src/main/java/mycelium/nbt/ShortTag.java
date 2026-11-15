package mycelium.nbt;

public record ShortTag(short value) implements Tag {

    @Override
    public TagType type() {
        return TagType.SHORT;
    }

    @Override
    public String toString() {
        return Snbt.format(this);
    }
}
