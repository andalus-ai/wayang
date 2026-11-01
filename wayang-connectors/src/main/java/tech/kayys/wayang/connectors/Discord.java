package tech.kayys.wayang.connectors;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import tech.kayys.wayang.runtime.ServiceHandler;

/**
 * Connector {@code discord}: posts a message through a channel webhook (no bot, no gateway connection).
 *
 * <p>Config: {@code webhookUrl} (a secret: anyone holding it can post; use {@code ${secret:NAME}}); must be
 * {@code https://discord.com/api/webhooks/<id>/<token>} (or discordapp.com) unless the operator overrides {@code baseUrl}
 * for a proxy. Inputs: {@code text} (max 2000), optional {@code username}, {@code threadId}. Mentions are suppressed
 * ({@code allowed_mentions: none}) so untrusted text cannot ping {@code @everyone}.
 */
public final class Discord {

    static final int MAX_TEXT = 2000;
    private static final Pattern PATH = Pattern.compile("/api/webhooks/[0-9]{5,25}/[A-Za-z0-9_-]{20,200}");
    private static final Pattern SNOWFLAKE = Pattern.compile("[0-9]{5,25}");

    private Discord() {
    }

    public static final class Sender implements ServiceHandler.Connector {
        private final HttpConnector http;
        private final boolean anyHost;

        public Sender(UrlGuard guard) {
            this(guard, false);
        }

        /** {@code anyHost} lets tests (and operators with an egress proxy) use a non-Discord host; the UrlGuard still applies. */
        Sender(UrlGuard guard, boolean anyHost) {
            this.http = new HttpConnector(guard);
            this.anyHost = anyHost;
        }

        @Override
        public Map<String, Object> call(Map<String, Object> inputs) throws Exception {
            throw new ServiceHandler.ConnectorException("discord needs node configuration", false);
        }

        @Override
        public Map<String, Object> call(Map<String, Object> config, Map<String, Object> inputs) throws Exception {
            if (!(config.get("webhookUrl") instanceof String url) || url.isBlank()) {
                throw new ServiceHandler.ConnectorException("discord needs a 'webhookUrl' (use ${secret:NAME})", false);
            }
            URI u;
            try {
                u = URI.create(url.trim());
            } catch (IllegalArgumentException e) {
                throw new ServiceHandler.ConnectorException("Invalid webhookUrl", false);
            }
            String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
            boolean official = "https".equals(u.getScheme()) && u.getUserInfo() == null && u.getPort() == -1 && u.getQuery() == null
                    && (host.equals("discord.com") || host.equals("discordapp.com") || host.endsWith(".discord.com"));
            if ((!official && !anyHost) || !PATH.matcher(u.getPath() == null ? "" : u.getPath()).matches()) {
                throw new ServiceHandler.ConnectorException("webhookUrl must be https://discord.com/api/webhooks/<id>/<token>", false);
            }
            if (!(inputs.get("text") instanceof String text) || text.isBlank()) {
                throw new ServiceHandler.ConnectorException("discord needs non-empty 'text'", false);
            }
            if (text.length() > MAX_TEXT) {
                throw new ServiceHandler.ConnectorException("Text longer than " + MAX_TEXT + " characters", false);
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("content", text);
            body.put("allowed_mentions", Map.of("parse", java.util.List.of()));
            if (inputs.get("username") instanceof String n && !n.isBlank()) {
                body.put("username", n.length() > 80 ? n.substring(0, 80) : n);
            }
            String query = "?wait=true";
            if (inputs.get("threadId") instanceof String t && !t.isBlank()) {
                if (!SNOWFLAKE.matcher(t).matches()) {
                    throw new ServiceHandler.ConnectorException("Invalid threadId", false);
                }
                query += "&thread_id=" + t;
            }
            Map<String, Object> cfg = new LinkedHashMap<>();
            cfg.put("url", url.trim() + query);
            cfg.put("method", "POST");
            Map<String, Object> res = http.call(cfg, Map.of("body", body));
            Map<String, Object> out = new LinkedHashMap<>();
            if (res.get("body") instanceof Map<?, ?> m) {
                out.put("messageId", m.get("id"));
                out.put("channelId", m.get("channel_id"));
            }
            return out;
        }
    }
}
