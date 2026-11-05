package tech.kayys.wayang.connectors;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import tech.kayys.wayang.runtime.ServiceHandler;

/**
 * Connector {@code grpc}: a unary RPC with JSON messages over the Connect protocol ({@code POST {url}/{service}/{method}},
 * {@code application/json}), which servers built with connect-go / connect-es / connect-java, Envoy and many gRPC gateways speak.
 *
 * <p><b>This is not native gRPC.</b> Native gRPC frames protobuf over HTTP/2 and reads its status from trailers, which needs the service's
 * compiled descriptors; use a gateway (Envoy gRPC-JSON transcoder, grpc-gateway, a Connect-enabled server) in front of such a service.
 * Streaming calls are not supported.
 *
 * <p>Config: {@code url} (server base), <b>{@code service}</b> (e.g. {@code acme.v1.Greeter}), <b>{@code method}</b> (e.g. {@code SayHello}),
 * {@code headers}, {@code outputMap}, {@code timeoutMs}. The node's {@code inputs} are the request message (JSON field names as the
 * server's JSON mapping expects). Output: {@code response} and the outputMap variables. A Connect error {@code {code, message}} fails the node:
 * {@code unavailable}, {@code deadline_exceeded}, {@code resource_exhausted} and {@code aborted} are retryable.
 */
public final class Grpc {

    private static final Pattern SERVICE = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*){0,8}");
    private static final Pattern METHOD = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,100}");
    private static final Set<String> RETRYABLE = Set.of("unavailable", "deadline_exceeded", "resource_exhausted", "aborted");

    private Grpc() {
    }

    public static final class Client implements ServiceHandler.Connector {
        private final HttpConnector http;

        public Client(UrlGuard guard) {
            this.http = new HttpConnector(guard);
        }

        @Override
        public Map<String, Object> call(Map<String, Object> inputs) throws Exception {
            throw new ServiceHandler.ConnectorException("grpc needs node configuration (url, service, method)", false);
        }

        @Override
        public Map<String, Object> call(Map<String, Object> config, Map<String, Object> inputs) throws Exception {
            String url = Proto.url(config, "grpc");
            String service = config.get("service") instanceof String s ? s : "";
            String method = config.get("method") instanceof String m ? m : "";
            if (!SERVICE.matcher(service).matches() || !METHOD.matcher(method).matches()) {
                throw new ServiceHandler.ConnectorException("grpc needs a valid 'service' (such as acme.v1.Greeter) and 'method'", false);
            }
            Map<String, Object> cfg = new LinkedHashMap<>();
            cfg.put("url", url.replaceAll("/+$", "") + "/" + service + "/" + method);
            cfg.put("method", "POST");
            cfg.put("timeoutMs", config.getOrDefault("timeoutMs", 30_000));
            cfg.put("returnErrorBody", true);
            Map<String, String> h = Proto.headers(config);
            h.put("Connect-Protocol-Version", "1");
            h.put("Accept", "application/json");
            cfg.put("headers", h);
            Map<String, Object> res = http.call(cfg, Map.of("body", inputs == null ? Map.of() : inputs));
            int status = res.get("status") instanceof Number n ? n.intValue() : 0;
            Object b = res.get("body");
            if (status >= 200 && status < 300) {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("response", b instanceof Map<?, ?> ? b : Map.of());
                Proto.outputMap(config, b, out);
                return out;
            }
            if (b instanceof Map<?, ?> e && e.get("code") instanceof String code) {
                throw new ServiceHandler.ConnectorException("RPC failed: " + Proto.clip(code, 40) + (e.get("message") != null
                        ? ": " + Proto.clip(e.get("message"), 300) : ""), RETRYABLE.contains(code));
            }
            throw new ServiceHandler.ConnectorException("The server answered HTTP " + status + " (is it a Connect/JSON endpoint?)",
                    Proto.retryableStatus(status));
        }
    }
}
