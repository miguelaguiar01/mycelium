package mycelium.nbt;

/**
 * An immutable NBT value. Tags compare by value: two trees are equal when they hold the same
 * data, whatever order their compound keys were read in.
 */
public sealed interface Tag
        permits ByteTag, ShortTag, IntTag, LongTag, FloatTag, DoubleTag, ByteArrayTag, StringTag,
                ListTag, CompoundTag, IntArrayTag, LongArrayTag {

    TagType type();
}
