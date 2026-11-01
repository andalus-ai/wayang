package tech.kayys.wayang.runtime.json;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class JsonTest {

    @Test
    void roundTripsNestedValues() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("s", "line1\nline2 \"quoted\" \\ \u0001 é");
        m.put("n", 3);
        m.put("d", new BigDecimal("1.50"));
        m.put("b", true);
        m.put("z", null);
        m.put("l", List.of("a", Map.of("k", List.of())));
        @SuppressWarnings("unchecked")
        Map<String, Object> back = (Map<String, Object>) Json.read(Json.write(m));
        assertEquals(m.get("s"), back.get("s"));
        assertEquals(0, new BigDecimal(3).compareTo((BigDecimal) back.get("n")));
        assertEquals(0, new BigDecimal("1.5").compareTo((BigDecimal) back.get("d")));
        assertEquals(true, back.get("b"));
        assertTrue(back.containsKey("z") && back.get("z") == null);
        assertEquals(List.of("a", Map.of("k", List.of())), back.get("l"));
    }

    @Test
    void rejectsMalformedInput() {
        for (String bad : new String[] { "", "{", "{\"a\":}", "[1,]", "\"abc", "{\"a\":1} x", "tru", "{1:2}", "\"\\q\"" }) {
            assertThrows(IllegalArgumentException.class, () -> Json.read(bad));
        }
    }

    @Test
    void deepNestingIsBounded() {
        String deep = "[".repeat(200) + "]".repeat(200);
        assertThrows(IllegalArgumentException.class, () -> Json.read(deep));
    }
}
