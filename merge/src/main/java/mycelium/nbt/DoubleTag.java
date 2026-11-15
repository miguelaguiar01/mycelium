package mycelium.nbt;

public record DoubleTag(double value) implements Tag {

    @Override
    public TagType type() {
        return TagType.DOUBLE;
    }

    @Override
    public String toString() {
        return Snbt.format(this);
    }
}
