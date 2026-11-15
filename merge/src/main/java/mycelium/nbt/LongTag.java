package mycelium.nbt;

public record LongTag(long value) implements Tag {

    @Override
    public TagType type() {
        return TagType.LONG;
    }

    @Override
    public String toString() {
        return Snbt.format(this);
    }
}
