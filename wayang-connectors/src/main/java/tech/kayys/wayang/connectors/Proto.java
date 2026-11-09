package tech.kayys.wayang.connectors;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import tech.kayys.wayang.runtime.ServiceHandler;

/** Small helpers shared by the protocol connectors (graphql, a2a, grpc, websocket). */
final class Proto {

    private static final Pattern VAR = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,63}");

    private Proto() {
    }

    static Map<String, String> headers(Map<String, Object> config) {
        Map<String, String> h = new LinkedHashMap<>();
        if (config.get("headers") instanceof Map<?, ?> m) {
            m.forEach((k, v) -> h.put(String.valueOf(k), String.valueOf(v)));
        }
        return h;
    }

    static String url(Map<String, Object> config, String what) throws ServiceHandler.ConnectorException {
        if (!(config.get("url") instanceof String u) || u.isBlank()) {
            throw new ServiceHandler.ConnectorException(what + " needs 'url'", false);
        }
        return u;
    }

    static String clip(Object v, int max) {
        String s = String.valueOf(v).strip();
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    /** Adds each outputMap entry (variable name to path) read from {@code source} into {@code out}. */
    static void outputMap(Map<String, Object> config, Object source, Map<String, Object> out) throws ServiceHandler.ConnectorException {
        if (config.get("outputMap") instanceof Map<?, ?> m) {
            for (var e : m.entrySet()) {
                String name = String.valueOf(e.getKey());
                if (!VAR.matcher(name).matches()) {
                    throw new ServiceHandler.ConnectorException("outputMap name '" + name + "' is not a valid variable name", false);
                }
                out.put(name, JsonPath.get(source, String.valueOf(e.getValue())));
            }
        }
    }

    /** What a non-2xx status means: connection-level and overload statuses may be retried. */
    static boolean retryableStatus(int status) {
        return status == 408 || status == 425 || status == 429 || status >= 500;
    }
}
