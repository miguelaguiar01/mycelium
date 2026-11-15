package mycelium.nbt;

public record ByteTag(byte value) implements Tag {

    @Override
    public TagType type() {
        return TagType.BYTE;
    }

    @Override
    public String toString() {
        return Snbt.format(this);
    }
}
