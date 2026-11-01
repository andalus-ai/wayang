package tech.kayys.wayang.connectors;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import tech.kayys.wayang.runtime.ServiceHandler;

/**
 * Connector {@code twilio}: send an SMS (or WhatsApp through Twilio) message.
 *
 * <p>Config: {@code accountSid}, {@code authToken} (as {@code ${secret:NAME}}), {@code from} (E.164 number, {@code MG...}
 * messaging service, or {@code whatsapp:+...}), <b>{@code allowedTo} (required, exact numbers)</b>, optional {@code baseUrl}.
 * Inputs: {@code to}, {@code body} (at most 1600 characters). Output: {@code sid, status}.
 */
public final class Twilio {

    private static final Pattern SID = Pattern.compile("AC[0-9a-fA-F]{32}");
    private static final Pattern NUMBER = Pattern.compile("(whatsapp:)?\\+[1-9][0-9]{6,14}");
    private static final Pattern FROM = Pattern.compile("((whatsapp:)?\\+[1-9][0-9]{6,14})|(MG[0-9a-fA-F]{32})");
    private static final String DEFAULT_BASE = "https://api.twilio.com";

    private Twilio() {
    }

    public static final class Sender implements ServiceHandler.Connector {
        private final HttpConnector http;

        public Sender(UrlGuard guard) {
            this.http = new HttpConnector(guard);
        }

        @Override
        public Map<String, Object> call(Map<String, Object> inputs) throws Exception {
            throw new ServiceHandler.ConnectorException("twilio needs node configuration", false);
        }

        @Override
        public Map<String, Object> call(Map<String, Object> config, Map<String, Object> inputs) throws Exception {
            String sid = Rest.segment(config.get("accountSid"), SID, "accountSid");
            String token = Rest.secret(config, "authToken", "twilio", 16);
            String from = Rest.segment(config.get("from"), FROM, "from");
            String to = Rest.segment(inputs.get("to"), NUMBER, "to number (E.164, e.g. +628123456789)");
            if (!(config.get("allowedTo") instanceof List<?> l) || l.stream().noneMatch(o -> String.valueOf(o).equals(to))) {
                throw new ServiceHandler.ConnectorException("Recipient is not in 'allowedTo'", false);
            }
            if (!from.startsWith("MG") && from.startsWith("whatsapp:") != to.startsWith("whatsapp:")) {
                // a WhatsApp sender can only reach WhatsApp recipients and the reverse
                throw new ServiceHandler.ConnectorException("'from' and 'to' must both be WhatsApp or both plain numbers", false);
            }
            String body = Rest.text(inputs.get("body"), "body", 1600, true);
            Map<String, String> form = new LinkedHashMap<>();
            form.put("To", to);
            form.put(from.startsWith("MG") ? "MessagingServiceSid" : "From", from);
            form.put("Body", body);
            StringBuilder sb = new StringBuilder();
            form.forEach((k, v) -> {
                if (sb.length() > 0) {
                    sb.append('&');
                }
                sb.append(k).append('=').append(URLEncoder.encode(v, StandardCharsets.UTF_8));
            });
            Map<String, String> h = Map.of("Authorization",
                    "Basic " + Base64.getEncoder().encodeToString((sid + ":" + token).getBytes(StandardCharsets.UTF_8)),
                    "Content-Type", "application/x-www-form-urlencoded");
            Map<String, Object> r = Rest.call(http, Rest.base(config, DEFAULT_BASE) + "/2010-04-01/Accounts/" + sid + "/Messages.json",
                    "POST", h, null, sb.toString());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("sid", r.get("sid"));
            out.put("status", r.get("status"));
            return out;
        }
    }
}
