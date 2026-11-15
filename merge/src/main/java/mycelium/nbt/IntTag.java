package mycelium.nbt;

public record IntTag(int value) implements Tag {

    @Override
    public TagType type() {
        return TagType.INT;
    }

    @Override
    public String toString() {
        return Snbt.format(this);
    }
}
