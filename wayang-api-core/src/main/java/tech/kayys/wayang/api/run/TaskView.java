package tech.kayys.wayang.api.run;

import java.time.Instant;
import java.util.Map;

/** A pending wait as the task inbox / run view shows it. {@code kind}: human, timer, message. */
public record TaskView(String token, String runId, String nodeId, String kind, String title, String assignee,
        Map<String, Object> info, Instant dueAt, String messageName, String correlationKey) {
}
