package tech.kayys.wayang.connectors;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import tech.kayys.wayang.runtime.ServiceHandler;

/**
 * Connector {@code teams}: posts an Adaptive Card message to a Microsoft Teams channel through a Workflows (or legacy
 * incoming) webhook. Config {@code webhookUrl} ({@code ${secret:NAME}}, https on logic.azure.com, *.webhook.office.com,
 * outlook.office.com or *.powerplatform.com); inputs {@code text} (max 10000), optional {@code title}.
 */
public final class Teams {

    static final int MAX_TEXT = 10_000;
    private static final List<String> HOSTS = List.of("logic.azure.com", "webhook.office.com", "outlook.office.com", "powerplatform.com");

    private Teams() {
    }

    public static final class Sender implements ServiceHandler.Connector {
        private final HttpConnector http;
        private final boolean anyHost;

        public Sender(UrlGuard guard) {
            this(guard, false);
        }

        Sender(UrlGuard guard, boolean anyHost) {
            this.http = new HttpConnector(guard);
            this.anyHost = anyHost;
        }

        @Override
        public Map<String, Object> call(Map<String, Object> inputs) throws Exception {
            throw new ServiceHandler.ConnectorException("teams needs node configuration", false);
        }

        @Override
        public Map<String, Object> call(Map<String, Object> config, Map<String, Object> inputs) throws Exception {
            if (!(config.get("webhookUrl") instanceof String url) || url.isBlank()) {
                throw new ServiceHandler.ConnectorException("teams needs a 'webhookUrl' (use ${secret:NAME})", false);
            }
            URI u;
            try {
                u = URI.create(url.trim());
            } catch (IllegalArgumentException e) {
                throw new ServiceHandler.ConnectorException("Invalid webhookUrl", false);
            }
            String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
            boolean official = "https".equals(u.getScheme()) && u.getUserInfo() == null
                    && HOSTS.stream().anyMatch(d -> host.equals(d) || host.endsWith("." + d));
            if (!official && !anyHost) {
                throw new ServiceHandler.ConnectorException("webhookUrl must be an https Teams / Power Automate webhook address", false);
            }
            String text = Rest.text(inputs.get("text"), "text", MAX_TEXT, true);
            String title = Rest.text(inputs.get("title"), "title", 200, false);
            List<Map<String, Object>> body = new java.util.ArrayList<>();
            if (title != null) {
                body.add(Map.of("type", "TextBlock", "text", title, "weight", "Bolder", "size", "Medium", "wrap", true));
            }
            body.add(Map.of("type", "TextBlock", "text", text, "wrap", true));
            Map<String, Object> card = new LinkedHashMap<>();
            card.put("type", "AdaptiveCard");
            card.put("$schema", "http://adaptivecards.io/schemas/adaptive-card.json");
            card.put("version", "1.4");
            card.put("body", body);
            Map<String, Object> msg = Map.of("type", "message", "attachments",
                    List.of(Map.of("contentType", "application/vnd.microsoft.card.adaptive", "content", card)));
            Rest.call(http, url.trim(), "POST", Map.of(), null, msg);
            return Map.of("ok", true);
        }
    }
}
