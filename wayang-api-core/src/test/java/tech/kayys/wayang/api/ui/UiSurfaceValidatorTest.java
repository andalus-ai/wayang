package tech.kayys.wayang.api.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class UiSurfaceValidatorTest {

    private static Map<String, Object> surface(Object root) {
        return Map.of("id", "s1", "title", "Result", "rootNode", root);
    }

    private static Map<String, Object> text(String t) {
        return Map.of("_type", "text", "text", t);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aPlainSurfaceIsAcceptedAndRebuiltWithKnownFieldsOnly() {
        Map<String, Object> raw = Map.of("id", "s1", "title", "Order 42", "evil", "x", "rootNode",
                Map.of("_type", "card", "title", "Summary", "hack", "<script>", "children", List.of(
                        Map.of("_type", "heading", "text", "Hi", "level", 99),
                        Map.of("_type", "metadata_row", "label", "Total", "value", "12"),
                        Map.of("_type", "table", "headers", List.of("a", "b"), "rows", List.of(List.of("1", "2"))),
                        Map.of("_type", "badge", "label", "ok", "variant", "nonsense"),
                        Map.of("_type", "button", "label", "Reload", "action", Map.of("_type", "refresh")),
                        Map.of("_type", "button", "label", "Open", "action", Map.of("_type", "navigate", "route", "/runs")))));
        var r = UiSurfaceValidator.validate(raw);
        assertTrue(r.ok(), r.problems().toString());
        assertFalse(r.surface().containsKey("evil"));
        var root = (Map<String, Object>) r.surface().get("rootNode");
        assertFalse(root.containsKey("hack"));
        var kids = (List<Map<String, Object>>) root.get("children");
        assertEquals(6, kids.size());
        assertEquals(6, kids.get(0).get("level"), "a level is clamped to 1..6");
        assertEquals("neutral", kids.get(3).get("variant"), "an unknown badge variant falls back");
    }

    @Test
    void thingsThatActOrFetchAreRefusedWithAPathNotTrusted() {
        for (Object bad : List.of(
                Map.of("_type", "image", "url", "https://tracker.example/p.png", "altText", "x"),
                Map.of("_type", "chart", "title", "t"),
                Map.of("_type", "script"),
                Map.of("_type", "button", "label", "Go", "action", Map.of("_type", "invoke", "command", "delete_all")),
                Map.of("_type", "button", "label", "Go", "action", Map.of("_type", "approve", "resourceId", "x")),
                Map.of("_type", "button", "label", "Go", "action", Map.of("_type", "submit", "target", "/x")),
                Map.of("_type", "button", "label", "Go", "action", Map.of("_type", "navigate", "route", "https://evil.example")),
                Map.of("_type", "button", "label", "Go", "action", Map.of("_type", "navigate", "route", "//evil.example")),
                Map.of("_type", "button", "label", "Go", "action", Map.of("_type", "navigate", "route", "/a\\b")))) {
            var r = UiSurfaceValidator.validate(surface(bad));
            assertFalse(r.ok(), bad.toString());
            assertTrue(r.problems().get(0).startsWith("rootNode"), r.problems().toString());
            assertEquals(null, r.surface());
        }
    }

    @Test
    void malformedInputIsAProblemNotAnException() {
        assertFalse(UiSurfaceValidator.validate("x").ok());
        assertFalse(UiSurfaceValidator.validate(null).ok());
        assertFalse(UiSurfaceValidator.validate(Map.of("id", "a", "title", "t")).ok());
        assertFalse(UiSurfaceValidator.validate(surface(Map.of("_type", "card", "children", "nope"))).ok());
        assertFalse(UiSurfaceValidator.validate(surface(Map.of("_type", "text", "text", 5))).ok());
        assertFalse(UiSurfaceValidator.validate(Map.of("id", "", "title", "t", "rootNode", text("x"))).ok(), "an id is required");
    }

    @Test
    void depthNodeCountAndTextAreLimited() {
        Map<String, Object> deep = text("leaf");
        for (int i = 0; i < UiSurfaceValidator.MAX_DEPTH + 1; i++) {
            deep = Map.of("_type", "container", "children", List.of(deep));
        }
        var d = UiSurfaceValidator.validate(surface(deep));
        assertFalse(d.ok());
        assertTrue(d.problems().stream().anyMatch(p -> p.contains("deeper")), d.problems().toString());

        List<Object> many = new ArrayList<>();
        for (int i = 0; i < UiSurfaceValidator.MAX_NODES + 10; i++) {
            many.add(text("n" + i));
        }
        var n = UiSurfaceValidator.validate(surface(Map.of("_type", "container", "children", many)));
        assertFalse(n.ok());
        assertEquals(1, n.problems().stream().filter(p -> p.contains("nodes")).count(), "reported once, not per node");

        assertFalse(UiSurfaceValidator.validate(surface(text("x".repeat(UiSurfaceValidator.MAX_TEXT + 1)))).ok());
        assertTrue(UiSurfaceValidator.validate(surface(text("x".repeat(UiSurfaceValidator.MAX_TEXT)))).ok());
        List<List<String>> rows = new ArrayList<>();
        for (int i = 0; i < UiSurfaceValidator.MAX_ROWS + 1; i++) {
            rows.add(List.of("c"));
        }
        assertFalse(UiSurfaceValidator.validate(surface(Map.of("_type", "table", "headers", List.of("h"), "rows", rows))).ok());
    }

    @Test
    void metadataKeepsOnlyPlainValues() {
        var r = UiSurfaceValidator.validate(Map.of("id", "s", "title", "t", "rootNode", text("x"),
                "metadata", Map.of("nested", Map.of("a", 1))));
        assertFalse(r.ok());
        var ok = UiSurfaceValidator.validate(Map.of("id", "s", "title", "t", "rootNode", text("x"), "metadata", Map.of("run", "r-1", "n", 3)));
        assertTrue(ok.ok(), ok.problems().toString());
    }

    private static Map<String, Object> buttonWith(Map<String, Object> action) {
        return Map.of("id", "s", "title", "t", "rootNode", Map.of("_type", "button", "label", "Approve", "action", action));
    }

    @Test
    void taskCompleteKeepsReferencesAndPlainOutputsOnly() {
        var ok = UiSurfaceValidator.validate(buttonWith(Map.of("_type", "task.complete", "runId", "run-1", "nodeId", "review", "outputs", Map.of("approved", true, "note", "ok"), "token", "secret-token", "url", "http://x")));
        assertTrue(ok.ok(), ok.problems().toString());
        @SuppressWarnings("unchecked")
        var action = (Map<String, Object>) ((Map<String, Object>) ok.surface().get("rootNode")).get("action");
        assertEquals("run-1", action.get("runId"));
        assertEquals(Map.of("approved", true, "note", "ok"), action.get("outputs"));
        assertFalse(action.containsKey("token"));
        assertFalse(action.containsKey("url"));
    }

    @Test
    void taskCompleteRefusesBadIdsAndStructuredOutputs() {
        assertFalse(UiSurfaceValidator.validate(buttonWith(Map.of("_type", "task.complete", "runId", "../x", "nodeId", "n"))).ok());
        assertFalse(UiSurfaceValidator.validate(buttonWith(Map.of("_type", "task.complete", "runId", "r"))).ok());
        assertFalse(UiSurfaceValidator.validate(buttonWith(Map.of("_type", "task.complete", "runId", "r", "nodeId", "n", "outputs", Map.of("x", Map.of("a", 1))))).ok());
        assertFalse(UiSurfaceValidator.validate(buttonWith(Map.of("_type", "task.complete", "runId", "r", "nodeId", "n", "outputs", List.of(1)))).ok());
    }
}
