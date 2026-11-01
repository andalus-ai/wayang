package tech.kayys.wayang.runtime;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Understands one provider's webhooks (Slack Events, WhatsApp Cloud, ...): proves a request really came from the
 * provider, answers its set-up handshake, and turns payloads into small flat events.
 *
 * <p>Implementations are stateless and see the trigger's configuration with secrets already resolved.
 */
public interface WebhookProvider {

    /** Request failed authentication or is malformed; the message is safe to log, never to echo secrets. */
    final class Rejected extends Exception {
        private static final long serialVersionUID = 1L;

        public Rejected(String message) {
            super(message);
        }
    }

    /**
     * @param headers lower-cased header names
     * @param body    the raw bytes exactly as received (signatures are computed over them)
     */
    void verify(Map<String, Object> config, Map<String, String> headers, byte[] body, Instant now) throws Rejected;

    /** A provider's set-up request (not an event): the text to answer with. Called for GET and for payload handshakes. */
    Optional<String> handshake(Map<String, Object> config, Map<String, String> query, Map<String, Object> json) throws Rejected;

    /** Events to start runs for, already filtered (allow-list, bots, unsupported types). {@code id} dedupes retries. */
    List<Event> events(Map<String, Object> config, Map<String, Object> json);

    record Event(String id, Map<String, Object> data) {
    }
}
