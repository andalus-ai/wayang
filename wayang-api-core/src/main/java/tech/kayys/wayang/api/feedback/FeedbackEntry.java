package tech.kayys.wayang.api.feedback;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One piece of feedback. Text has already been scrubbed of credentials by {@link FeedbackService}.
 *
 * @param status {@code open} (new) or {@code done} (an admin has dealt with it)
 * @param kind    {@code rating}, {@code comment}, {@code bug}, {@code idea} or {@code error}
 * @param rating  {@code up}, {@code down} or null
 * @param context small, named details (run id, screen, app version, stack); never bodies or secrets
 */
public record FeedbackEntry(String id, String tenant, Instant time, String user, String kind, String rating, String message,
        Map<String, String> context, String status) {

    public static final String OPEN = "open";
    public static final String DONE = "done";

    /** A new entry is open. */
    public FeedbackEntry(String id, String tenant, Instant time, String user, String kind, String rating, String message, Map<String, String> context) {
        this(id, tenant, time, user, kind, rating, message, context, OPEN);
    }

    public FeedbackEntry {
        status = DONE.equals(status) ? DONE : OPEN;
        context = context == null ? Map.of() : Map.copyOf(context);
        message = message == null ? "" : message;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("time", time.toString());
        m.put("user", user);
        m.put("kind", kind);
        m.put("status", status);
        if (rating != null) {
            m.put("rating", rating);
        }
        m.put("message", message);
        m.put("context", new LinkedHashMap<>(context));
        return m;
    }
}
