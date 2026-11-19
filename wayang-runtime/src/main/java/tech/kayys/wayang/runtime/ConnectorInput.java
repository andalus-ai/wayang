package tech.kayys.wayang.runtime;

import java.util.Collection;
import java.util.Map;

/**
 * One declared input of a connector: what a node can be given, and whether it is expected.
 *
 * <p>{@code mandatory} is advisory. A mandatory input that is missing (null, empty text, empty list or empty object) never stops a run
 * and never fails a node: the connector gets nothing for it and answers with an empty result, and the engine adds a human-readable entry
 * to the node's {@code warnings} output so the gap is visible. Studio shows mandatory inputs as expected connections.
 */
public record ConnectorInput(String name, String type, boolean mandatory, String description) {

    public ConnectorInput {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("input name must not be blank");
        }
        type = type == null || type.isBlank() ? "any" : type;
        description = description == null ? "" : description;
    }

    public static ConnectorInput required(String name, String type, String description) {
        return new ConnectorInput(name, type, true, description);
    }

    public static ConnectorInput optional(String name, String type, String description) {
        return new ConnectorInput(name, type, false, description);
    }

    /** True when {@code value} counts as "not provided". */
    public static boolean isMissing(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof CharSequence s) {
            return s.toString().isBlank();
        }
        if (value instanceof Collection<?> c) {
            return c.isEmpty();
        }
        return value instanceof Map<?, ?> m && m.isEmpty();
    }

    /** The descriptor form, for the component catalogue: {@code {name, type, mandatory, description}}. */
    public Map<String, Object> toMap() {
        return Map.of("name", name, "type", type, "mandatory", mandatory, "description", description);
    }
}
