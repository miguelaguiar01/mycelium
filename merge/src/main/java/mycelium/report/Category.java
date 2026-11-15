package mycelium.report;

/** What a report entry is about. The JSON report uses the lower-case names. */
public enum Category {
    BLOCK, BIOME, UNIT, BLOCK_ENTITY, CONTAINER_SLOT, ENTITY, ENTITY_REFERENCE, PLAYER, LEVEL, FILE,
    UNKNOWN_FIELD, NO_ANCESTOR, REFUSED
}
