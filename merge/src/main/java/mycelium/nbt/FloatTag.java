package mycelium.nbt;

public record FloatTag(float value) implements Tag {

    @Override
    public TagType type() {
        return TagType.FLOAT;
    }

    @Override
    public String toString() {
        return Snbt.format(this);
    }
}
