package tech.kayys.wayang.api.feedback;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One piece of feedback. Text has already been scrubbed of credentials by {@link FeedbackService}.
 *
 * @param kind    {@code rating}, {@code comment}, {@code bug}, {@code idea} or {@code error}
 * @param rating  {@code up}, {@code down} or null
 * @param context small, named details (run id, screen, app version, stack); never bodies or secrets
 */
public record FeedbackEntry(String id, String tenant, Instant time, String user, String kind, String rating, String message,
        Map<String, String> context) {
    public FeedbackEntry {
        context = context == null ? Map.of() : Map.copyOf(context);
        message = message == null ? "" : message;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("time", time.toString());
        m.put("user", user);
        m.put("kind", kind);
        if (rating != null) {
            m.put("rating", rating);
        }
        m.put("message", message);
        m.put("context", new LinkedHashMap<>(context));
        return m;
    }
}
