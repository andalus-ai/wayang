package tech.kayys.wayang.api.definition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class StoredDefinitionTest {

    @Test
    void constructorTakesDeepSnapshotOfNestedMapsAndLists() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("label", "original");
        List<Object> items = new ArrayList<>();
        items.add(nested);
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("items", items);
        document.put("nullable", null);

        StoredDefinition definition = definition(document);

        nested.put("label", "mutated");
        items.add("later");
        document.put("new-field", true);

        Map<?, ?> storedItem = (Map<?, ?>) ((List<?>) definition.document().get("items")).get(0);
        assertEquals("original", storedItem.get("label"));
        assertEquals(1, ((List<?>) definition.document().get("items")).size());
        assertEquals(true, definition.document().containsKey("nullable"));
        assertEquals(false, definition.document().containsKey("new-field"));
    }

    @Test
    void nestedMapsAndListsAreImmutable() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("value", "before");
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("nested", nested);
        document.put("items", new ArrayList<>(List.of("one")));

        StoredDefinition definition = definition(document);
        Map<?, ?> storedNested = (Map<?, ?>) definition.document().get("nested");

        assertThrows(UnsupportedOperationException.class, () -> mutateMap(storedNested));
        assertThrows(UnsupportedOperationException.class, () -> mutateList((List<?>) definition.document().get("items")));
    }

    @Test
    void publishedSnapshotDoesNotChangeWhenDraftSourceChanges() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("status", "draft");
        Map<String, Object> draftSource = new LinkedHashMap<>();
        draftSource.put("metadata", nested);

        StoredDefinition draft = definition(draftSource);
        StoredDefinition published = new StoredDefinition("tenant", DefinitionKind.PROCESS, "flow", 1,
                draft.revision(), draft.document(), Instant.parse("2026-01-01T00:00:00Z"), "publisher");

        nested.put("status", "changed");
        Map<?, ?> publishedMetadata = (Map<?, ?>) published.document().get("metadata");
        assertEquals("draft", publishedMetadata.get("status"));
    }

    @SuppressWarnings("unchecked")
    private static void mutateMap(Map<?, ?> value) {
        ((Map<String, Object>) value).put("value", "after");
    }

    @SuppressWarnings("unchecked")
    private static void mutateList(List<?> value) {
        ((List<Object>) value).add("two");
    }

    private static StoredDefinition definition(Map<String, Object> document) {
        return new StoredDefinition("tenant", DefinitionKind.PROCESS, "flow", 0, 1, document,
                Instant.parse("2026-01-01T00:00:00Z"), "author");
    }
}
