package tech.kayys.wayang.connectors;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import tech.kayys.wayang.runtime.ServiceHandler;

/** Shared plumbing for the JSON API connectors: credentials, base URL, safe path segments, uniform result shape. */
final class Rest {

    private Rest() {
    }

    /** A credential taken from config: single token, no whitespace, sane length. */
    static String secret(Map<String, Object> config, String key, String what, int minLen) throws ServiceHandler.ConnectorException {
        if (!(config.get(key) instanceof String t) || t.length() < minLen || t.length() > 4096 || t.matches(".*\\s.*")) {
            throw new ServiceHandler.ConnectorException(what + " needs '" + key + "' (use ${secret:NAME})", false);
        }
        return t;
    }

    static String base(Map<String, Object> config, String dflt) {
        return config.get("baseUrl") instanceof String b && !b.isBlank() ? b.replaceAll("/+$", "") : dflt;
    }

    /** A value that will become part of a URL path must match, so it can never add segments, queries or traversal. */
    static String segment(Object v, Pattern allowed, String what) throws ServiceHandler.ConnectorException {
        if (!(v instanceof String s) || !allowed.matcher(s).matches()) {
            throw new ServiceHandler.ConnectorException("Invalid " + what, false);
        }
        return s;
    }

    static String text(Object v, String what, int max, boolean required) throws ServiceHandler.ConnectorException {
        if (v == null || (v instanceof String s0 && s0.isBlank())) {
            if (required) {
                throw new ServiceHandler.ConnectorException("Missing " + what, false);
            }
            return null;
        }
        if (!(v instanceof String s)) {
            throw new ServiceHandler.ConnectorException(what + " must be text", false);
        }
        if (s.length() > max) {
            throw new ServiceHandler.ConnectorException(what + " longer than " + max + " characters", false);
        }
        return s;
    }

    static int limit(Object v, int dflt, int max) {
        return v instanceof Number n ? (int) Math.max(1, Math.min(max, n.longValue())) : dflt;
    }

    /** Allow-list check: exact entries, or {@code prefix/*} for "everything under". An empty list allows nothing. */
    static void allowed(Object list, String value, String what) throws ServiceHandler.ConnectorException {
        if (list instanceof List<?> l) {
            for (Object o : l) {
                String a = String.valueOf(o);
                if (a.equalsIgnoreCase(value) || (a.endsWith("/*") && value.toLowerCase().startsWith(a.substring(0, a.length() - 1).toLowerCase()))) {
                    return;
                }
            }
        }
        throw new ServiceHandler.ConnectorException(what + " '" + value + "' is not in the allow-list", false);
    }

    static Map<String, Object> call(HttpConnector http, String url, String method, Map<String, String> headers, Map<String, Object> query,
            Object body) throws Exception {
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("url", url);
        cfg.put("method", method);
        cfg.put("timeoutMs", 20_000);
        cfg.put("headers", headers);
        Map<String, Object> in = new LinkedHashMap<>();
        if (query != null && !query.isEmpty()) {
            in.put("query", query);
        }
        if (body != null) {
            in.put("body", body);
        }
        Map<String, Object> res = http.call(cfg, in);
        Object b = res.get("body");
        Map<String, Object> out = new LinkedHashMap<>();
        if (b instanceof Map<?, ?> m) {
            m.forEach((k, v) -> out.put(String.valueOf(k), v));
        } else if (b instanceof List<?> l) {
            out.put("items", l);
        }
        return out;
    }
}
