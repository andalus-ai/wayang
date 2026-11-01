package tech.kayys.wayang.api;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts API view records (and lists/maps of them) into plain JSON-ready
 * values: records become maps, enums their names, instants ISO-8601 text. Used
 * by transports that do not have an object mapper (gRPC, WebSocket).
 */
public final class Views {

    private Views() {
    }

    public static Object plain(Object o) {
        return plain(o, 0);
    }

    private static Object plain(Object o, int depth) {
        if (depth > 32) {
            throw new IllegalArgumentException("View nesting too deep");
        }
        if (o == null || o instanceof String || o instanceof Number || o instanceof Boolean) {
            return o;
        }
        if (o instanceof Enum<?> e) {
            return e.name();
        }
        if (o instanceof Instant i) {
            return i.toString();
        }
        if (o instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), plain(v, depth + 1)));
            return out;
        }
        if (o instanceof Iterable<?> it) {
            List<Object> out = new ArrayList<>();
            it.forEach(v -> out.add(plain(v, depth + 1)));
            return out;
        }
        if (o instanceof Record r) {
            Map<String, Object> out = new LinkedHashMap<>();
            try {
                for (RecordComponent c : r.getClass().getRecordComponents()) {
                    out.put(c.getName(), plain(c.getAccessor().invoke(r), depth + 1));
                }
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
            return out;
        }
        return String.valueOf(o);
    }
}
