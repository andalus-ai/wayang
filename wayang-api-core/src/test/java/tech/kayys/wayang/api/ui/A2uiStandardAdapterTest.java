package tech.kayys.wayang.api.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class A2uiStandardAdapterTest {

    private static Map<String, Object> comp(String id, String type, Map<String, Object> props) {
        return Map.of("id", id, "component", Map.of(type, props));
    }

    private static Map<String, Object> lit(String s) {
        return Map.of("literalString", s);
    }

    private static Map<String, Object> list(String... ids) {
        return Map.of("explicitList", List.of(ids));
    }

    @Test
    void standardCatalogSurfaceIsConvertedAndChecked() {
        var raw = Map.<String, Object>of("id", "s1", "title", "Hello", "root", "col", "components", List.of(
                comp("col", "Column", Map.of("children", list("h", "card"))),
                comp("h", "Text", Map.of("text", lit("Title"), "usageHint", "h2")),
                comp("card", "Card", Map.of("child", "row")),
                comp("row", "Row", Map.of("children", list("t"))),
                comp("t", "Text", Map.of("text", lit("body")))));
        var r = UiSurfaceValidator.validate(raw);
        assertTrue(r.ok(), r.problems().toString());
        @SuppressWarnings("unchecked")
        var root = (Map<String, Object>) r.surface().get("rootNode");
        assertEquals("container", root.get("_type"));
        @SuppressWarnings("unchecked")
        var kids = (List<Map<String, Object>>) root.get("children");
        assertEquals("heading", kids.get(0).get("_type"));
        assertEquals(2, ((Number) kids.get(0).get("level")).intValue());
        assertEquals("card", kids.get(1).get("_type"));
    }

    @Test
    void bindingsButtonsAndUnknownTypesAreRefused() {
        var bound = Map.<String, Object>of("root", "t", "components", List.of(comp("t", "Text", Map.of("text", Map.of("path", "/x")))));
        assertFalse(UiSurfaceValidator.validate(bound).ok());
        var button = Map.<String, Object>of("root", "b", "components", List.of(comp("b", "Button", Map.of("child", "t"))));
        assertTrue(UiSurfaceValidator.validate(button).problems().get(0).contains("Button"));
        var image = Map.<String, Object>of("root", "i", "components", List.of(comp("i", "Image", Map.of("url", lit("http://x")))));
        assertTrue(UiSurfaceValidator.validate(image).problems().get(0).contains("not supported yet"));
    }

    @Test
    void cyclesDanglingIdsAndBadRootAreProblemsNotCrashes() {
        var cycle = Map.<String, Object>of("root", "a", "components", List.of(
                comp("a", "Column", Map.of("children", list("b"))),
                comp("b", "Column", Map.of("children", list("a")))));
        assertFalse(UiSurfaceValidator.validate(cycle).ok());
        var dangling = Map.<String, Object>of("root", "a", "components", List.of(comp("a", "Column", Map.of("children", list("zz")))));
        assertFalse(UiSurfaceValidator.validate(dangling).ok());
        assertFalse(UiSurfaceValidator.validate(Map.of("root", "nope", "components", List.of())).ok());
        assertFalse(UiSurfaceValidator.validate(Map.of("components", "x")).ok());
        var template = Map.<String, Object>of("root", "a", "components", List.of(comp("a", "Column", Map.of("children", Map.of("template", Map.of())))));
        assertFalse(UiSurfaceValidator.validate(template).ok());
    }

    @Test
    void oversizedComponentListIsRefused() {
        var many = new java.util.ArrayList<Object>();
        for (int i = 0; i < 400; i++) {
            many.add(comp("c" + i, "Text", Map.of("text", lit("x"))));
        }
        assertFalse(UiSurfaceValidator.validate(Map.of("root", "c0", "components", many)).ok());
    }
}
