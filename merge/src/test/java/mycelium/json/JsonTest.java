package mycelium.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonTest {

    @Test
    void whatItWritesItReadsBack() {
        Object value = Json.parse("{\"stats\": {\"minecraft:custom\": {\"minecraft:jump\": 3, \"big\": 4294967296}},"
                + " \"list\": [true, null, \"a\\\"b\", 1.5], \"DataVersion\": 5023}");
        assertEquals(value, Json.parse(Json.write(value)));
        Map<String, Object> root = Json.object(value, "root");
        assertEquals(5023, root.get("DataVersion"));
        assertEquals(4294967296L, Json.object(Json.object(root.get("stats"), "stats").get("minecraft:custom"), "custom").get("big"));
        assertEquals(List.of("stats", "list", "DataVersion"), List.copyOf(root.keySet()), "keys keep their order");
    }

    @Test
    void nestingIsLimitedSoACraftedFileCannotOverflowTheStack() {
        // Player files of other people are read as JSON; 100,000 brackets used to end the run with a stack overflow.
        String deep = "[".repeat(100_000) + "]".repeat(100_000);
        assertThrows(IllegalArgumentException.class, () -> Json.parse(deep));
        String fine = "[".repeat(200) + "]".repeat(200);
        assertEquals(fine, Json.write(Json.parse(fine)).replaceAll("\\s", ""));
    }
}
