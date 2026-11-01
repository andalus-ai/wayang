package tech.kayys.wayang.connectors;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import tech.kayys.wayang.runtime.ServiceHandler;

/**
 * Connector {@code resend}: send email through the Resend HTTP API (no SMTP needed).
 *
 * <p>Config: {@code apiKey} (as {@code ${secret:NAME}}), {@code from}, <b>{@code allowedRecipients} (required: exact addresses
 * or {@code @domain.tld})</b>, optional {@code baseUrl}. Inputs: {@code to} (text or list, at most 20), {@code subject},
 * {@code text} and/or {@code html}, optional {@code replyTo}. Output: {@code id}.
 */
public final class Resend {

    private static final Pattern ADDRESS = Pattern.compile("[A-Za-z0-9._%+'-]{1,64}@[A-Za-z0-9.-]{1,253}\\.[A-Za-z]{2,24}");
    private static final Pattern FROM = Pattern.compile("([^<>\\p{Cntrl}\"]{1,80} )?<?[A-Za-z0-9._%+'-]{1,64}@[A-Za-z0-9.-]{1,253}\\.[A-Za-z]{2,24}>?");
    private static final String DEFAULT_BASE = "https://api.resend.com";

    private Resend() {
    }

    public static final class Sender implements ServiceHandler.Connector {
        private final HttpConnector http;

        public Sender(UrlGuard guard) {
            this.http = new HttpConnector(guard);
        }

        @Override
        public Map<String, Object> call(Map<String, Object> inputs) throws Exception {
            throw new ServiceHandler.ConnectorException("resend needs node configuration", false);
        }

        @Override
        public Map<String, Object> call(Map<String, Object> config, Map<String, Object> inputs) throws Exception {
            String key = Rest.secret(config, "apiKey", "resend", 8);
            String from = Rest.segment(config.get("from"), FROM, "from address");
            List<String> to = new ArrayList<>();
            Object t = inputs.get("to");
            for (Object o : t instanceof List<?> l ? l : List.of(t == null ? "" : t)) {
                String a = Rest.segment(o, ADDRESS, "recipient address");
                allow(config.get("allowedRecipients"), a);
                to.add(a);
            }
            if (to.isEmpty() || to.size() > 20) {
                throw new ServiceHandler.ConnectorException("Between 1 and 20 recipients are required", false);
            }
            String subject = MailWire.clean(Rest.text(inputs.get("subject"), "subject", 300, true), "subject");
            String text = Rest.text(inputs.get("text"), "text", 500_000, false);
            String html = Rest.text(inputs.get("html"), "html", 500_000, false);
            if (text == null && html == null) {
                throw new ServiceHandler.ConnectorException("Need 'text' or 'html'", false);
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("from", from);
            body.put("to", to);
            body.put("subject", subject);
            if (text != null) {
                body.put("text", text);
            }
            if (html != null) {
                body.put("html", html);
            }
            if (inputs.get("replyTo") != null) {
                body.put("reply_to", Rest.segment(inputs.get("replyTo"), ADDRESS, "replyTo address"));
            }
            Map<String, Object> r = Rest.call(http, Rest.base(config, DEFAULT_BASE) + "/emails", "POST",
                    Map.of("Authorization", "Bearer " + key), null, body);
            return Map.of("id", r.get("id") == null ? "" : r.get("id"));
        }

        private static void allow(Object list, String address) throws ServiceHandler.ConnectorException {
            String a = address.toLowerCase(Locale.ROOT);
            if (list instanceof List<?> l) {
                for (Object o : l) {
                    String e = String.valueOf(o).toLowerCase(Locale.ROOT);
                    if (e.equals(a) || (e.startsWith("@") && a.endsWith(e))) {
                        return;
                    }
                }
            }
            throw new ServiceHandler.ConnectorException("Recipient '" + address + "' is not in 'allowedRecipients'", false);
        }
    }
}
