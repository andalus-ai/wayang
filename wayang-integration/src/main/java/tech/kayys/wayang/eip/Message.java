package tech.kayys.wayang.eip;

import java.util.LinkedHashMap;
import java.util.Map;

import tech.kayys.wayang.expression.Scope;

/** Immutable message: headers (metadata/routing) and a body (any JSON-like value). */
public record Message(Map<String, Object> headers, Object body) {
    public Message {
        headers = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(headers == null ? Map.of() : headers));
    }

    public static Message of(Object body) {
        return new Message(Map.of(), body);
    }

    public Message withBody(Object newBody) {
        return new Message(headers, newBody);
    }

    public Message withHeader(String name, Object value) {
        Map<String, Object> h = new LinkedHashMap<>(headers);
        h.put(name, value);
        return new Message(h, body);
    }

    /** Expression scope: {@code body} and {@code headers}; body fields are not hoisted, to keep names unambiguous. */
    public Scope scope(Scope base) {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("body", body);
        vars.put("headers", headers);
        return base.child(vars);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("headers", headers);
        m.put("body", body);
        return m;
    }
}
