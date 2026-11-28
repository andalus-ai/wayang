package tech.kayys.wayang.api.definition;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A definition document. {@code version == 0} is the editable draft (with a
 * {@code revision} counter for optimistic concurrency); {@code version >= 1}
 * are immutable published versions.
 *
 * <p>The document is a JSON tree. Construction takes a deep immutable
 * snapshot so callers cannot mutate a stored draft or published version
 * through nested maps or lists.</p>
 */
public record StoredDefinition(String tenantId, DefinitionKind kind, String id, int version, long revision,
        Map<String, Object> document, Instant updatedAt, String updatedBy) {
    public StoredDefinition {
        document = immutableObject(document);
    }

    public boolean isDraft() {
        return version == 0;
    }

    private static Map<String, Object> immutableObject(Map<String, Object> source) {
        if (source == null) {
            throw new IllegalArgumentException("Definition document must not be null");
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key == null) {
                throw new IllegalArgumentException("Definition document keys must not be null");
            }
            copy.put(key, immutableValue(value));
        });
        return Collections.unmodifiableMap(copy);
    }

    private static Object immutableValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, nestedValue) -> {
                if (!(key instanceof String stringKey)) {
                    throw new IllegalArgumentException("Definition document object keys must be strings");
                }
                copy.put(stringKey, immutableValue(nestedValue));
            });
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            list.forEach(item -> copy.add(immutableValue(item)));
            return Collections.unmodifiableList(copy);
        }
        return value;
    }
}
