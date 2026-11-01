package tech.kayys.wayang.bpmn;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ProcessMapsTest {

    private static Map<String, Object> doc() {
        return Map.of("id", "p", "name", "P",
                "elements", List.of(Map.of("id", "s", "type", "start_event"),
                        Map.of("id", "t", "type", "SERVICE_TASK", "config", Map.of("connector", "x")),
                        Map.of("id", "e", "type", "END_EVENT")),
                "flows", List.of(Map.of("id", "f1", "source", "s", "target", "t"),
                        Map.of("id", "f2", "source", "t", "target", "e")));
    }

    @Test
    void roundTrips() {
        ProcessModel m = ProcessMaps.fromMap(doc());
        assertTrue(m.isValid());
        ProcessModel back = ProcessMaps.fromMap(ProcessMaps.toMap(m));
        assertEquals(m.elements(), back.elements());
        assertEquals(m.flows(), back.flows());
        assertEquals("x", back.elements().get("t").config().get("connector"));
    }

    @Test
    void rejectsMalformedDocuments() {
        var typo = new java.util.LinkedHashMap<>(doc());
        typo.put("element", List.of());
        assertThrows(IllegalArgumentException.class, () -> ProcessMaps.fromMap(typo));
        var badType = new java.util.LinkedHashMap<>(doc());
        badType.put("elements", List.of(Map.of("id", "s", "type", "NOPE")));
        assertThrows(IllegalArgumentException.class, () -> ProcessMaps.fromMap(badType));
        var dup = new java.util.LinkedHashMap<>(doc());
        dup.put("elements", List.of(Map.of("id", "s", "type", "START_EVENT"), Map.of("id", "s", "type", "END_EVENT")));
        assertThrows(IllegalArgumentException.class, () -> ProcessMaps.fromMap(dup));
        assertThrows(IllegalArgumentException.class, () -> ProcessMaps.fromMap(Map.of("name", "no id")));
        var noSrc = new java.util.LinkedHashMap<>(doc());
        noSrc.put("flows", List.of(Map.of("id", "f1", "target", "t")));
        assertThrows(IllegalArgumentException.class, () -> ProcessMaps.fromMap(noSrc));
    }

    @Test
    void sizeLimitsApply() {
        var many = new java.util.ArrayList<Object>();
        for (int i = 0; i < 2001; i++) {
            many.add(Map.of("id", "n" + i, "type", "SERVICE_TASK"));
        }
        assertThrows(IllegalArgumentException.class, () -> ProcessMaps.fromMap(Map.of("id", "p", "elements", many)));
    }
}
