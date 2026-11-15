package mycelium.chunk;

import mycelium.nbt.CompoundTag;
import mycelium.nbt.IntArrayTag;
import mycelium.nbt.IntTag;
import mycelium.nbt.ListTag;
import mycelium.nbt.Tag;
import java.util.ArrayList;
import java.util.List;

/** Converts between an entity chunk's root NBT and {@link EntityChunk}. */
public final class EntityChunkCodec {

    private static final String DATA_VERSION = "DataVersion";
    private static final String POSITION = "Position";
    private static final String ENTITIES = "Entities";

    private EntityChunkCodec() {
    }

    public static EntityChunk decode(CompoundTag root) throws ChunkFormatException {
        int[] position = Fields.requiredInts(root, POSITION, 2, "entity chunk");
        ChunkPos pos = new ChunkPos(position[0], position[1]);
        String where = "entity chunk " + pos.x() + "," + pos.z();
        int dataVersion = Fields.requiredInt(root, DATA_VERSION, where);
        DataVersions.require(dataVersion, where);
        List<CompoundTag> entities = new ArrayList<>();
        for (Tag element : Fields.optionalList(root, ENTITIES, where)) {
            entities.add(Fields.compound(element, where + " " + ENTITIES));
        }
        return new EntityChunk(dataVersion, pos, entities, root.without(DATA_VERSION, POSITION, ENTITIES));
    }

    public static CompoundTag encode(EntityChunk chunk) {
        return CompoundTag.builder()
                .put(POSITION, new IntArrayTag(new int[] {chunk.pos().x(), chunk.pos().z()}))
                .put(DATA_VERSION, new IntTag(chunk.dataVersion()))
                .put(ENTITIES, ListTag.of(chunk.entities()))
                .putAll(chunk.extra())
                .build();
    }
}
