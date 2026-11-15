package mycelium.chunk;

import mycelium.nbt.ByteArrayTag;
import mycelium.nbt.ByteTag;
import mycelium.nbt.CompoundTag;
import mycelium.nbt.IntArrayTag;
import mycelium.nbt.IntTag;
import mycelium.nbt.ListTag;
import mycelium.nbt.LongArrayTag;
import mycelium.nbt.LongTag;
import mycelium.nbt.StringTag;
import mycelium.nbt.Tag;

/** Typed access to compound fields. A missing required field or a wrong type is a format error. */
final class Fields {

    private Fields() {
    }

    static <T extends Tag> T required(CompoundTag tag, String key, Class<T> type, String where)
            throws ChunkFormatException {
        T value = optional(tag, key, type, where);
        if (value == null) {
            throw new ChunkFormatException(where + ": missing " + key);
        }
        return value;
    }

    /** The field, or null if absent. */
    static <T extends Tag> T optional(CompoundTag tag, String key, Class<T> type, String where)
            throws ChunkFormatException {
        Tag value = tag.get(key);
        if (value == null) {
            return null;
        }
        if (!type.isInstance(value)) {
            throw new ChunkFormatException(where + ": " + key + " is a " + value.type() + ", expected "
                    + type.getSimpleName().replace("Tag", "").toLowerCase());
        }
        return type.cast(value);
    }

    static int requiredInt(CompoundTag tag, String key, String where) throws ChunkFormatException {
        return required(tag, key, IntTag.class, where).value();
    }

    static byte requiredByte(CompoundTag tag, String key, String where) throws ChunkFormatException {
        return required(tag, key, ByteTag.class, where).value();
    }

    static String requiredString(CompoundTag tag, String key, String where) throws ChunkFormatException {
        return required(tag, key, StringTag.class, where).value();
    }

    static long optionalLong(CompoundTag tag, String key, long fallback, String where) throws ChunkFormatException {
        LongTag value = optional(tag, key, LongTag.class, where);
        return value == null ? fallback : value.value();
    }

    static ListTag optionalList(CompoundTag tag, String key, String where) throws ChunkFormatException {
        ListTag value = optional(tag, key, ListTag.class, where);
        return value == null ? ListTag.empty() : value;
    }

    static CompoundTag optionalCompound(CompoundTag tag, String key, String where) throws ChunkFormatException {
        return optional(tag, key, CompoundTag.class, where);
    }

    static ByteArrayTag optionalBytes(CompoundTag tag, String key, String where) throws ChunkFormatException {
        return optional(tag, key, ByteArrayTag.class, where);
    }

    static LongArrayTag optionalLongs(CompoundTag tag, String key, String where) throws ChunkFormatException {
        return optional(tag, key, LongArrayTag.class, where);
    }

    static int[] requiredInts(CompoundTag tag, String key, int length, String where) throws ChunkFormatException {
        int[] value = required(tag, key, IntArrayTag.class, where).ints();
        if (value.length != length) {
            throw new ChunkFormatException(where + ": " + key + " has " + value.length + " ints, expected " + length);
        }
        return value;
    }

    static CompoundTag compound(Tag element, String where) throws ChunkFormatException {
        if (element instanceof CompoundTag compound) {
            return compound;
        }
        throw new ChunkFormatException(where + ": element is a " + element.type() + ", expected compound");
    }
}
