package tech.kayys.wayang.connectors;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tech.kayys.wayang.runtime.OAuthTokenEndpoint;
import tech.kayys.wayang.runtime.ServiceHandler;

/** Token endpoint client over {@link HttpConnector} (SSRF guard, size and time limits apply). */
public final class OAuthHttp implements OAuthTokenEndpoint {

    private static final Pattern ERR = Pattern.compile("\"error\"\\s*:\\s*\"([a-z_]{1,40})\"");
    private final HttpConnector http;

    public OAuthHttp(UrlGuard guard) {
        this.http = new HttpConnector(guard);
    }

    @Override
    public Map<String, Object> post(String url, Map<String, String> form) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (var e : form.entrySet()) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)).append('=')
                    .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("url", url);
        cfg.put("method", "POST");
        cfg.put("timeoutMs", 15_000);
        cfg.put("headers", Map.of("Content-Type", "application/x-www-form-urlencoded", "Accept", "application/json"));
        try {
            Map<String, Object> res = http.call(cfg, Map.of("body", sb.toString()));
            if (res.get("body") instanceof Map<?, ?> m) {
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) m;
                return typed;
            }
            throw new ServiceHandler.ConnectorException("Token endpoint did not answer with JSON", false);
        } catch (ServiceHandler.ConnectorException e) {
            Matcher m = ERR.matcher(String.valueOf(e.getMessage()));
            if (m.find()) {
                throw new OAuthError(m.group(1));
            }
            throw e;
        }
    }
}
