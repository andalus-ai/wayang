package tech.kayys.wayang.bpmn;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Execution policy of one element, read from its configuration:
 *
 * <ul>
 * <li>{@code retries} - extra attempts after the first (0..{@value #MAX_RETRIES}, default 0). Only for service,
 * agent and EIP tasks; retrying is opt-in because the engine retries <em>every</em> failure and
 * connector calls may have side effects (make them idempotent first).</li>
 * <li>{@code retryDelay} (default PT1S), {@code retryMaxDelay} (default PT5M), {@code retryBackoff} (default 2.0).</li>
 * <li>{@code onError} - {@code fail} (default: the run fails) or {@code continue} (after the last attempt the
 * node completes with outputs {@code failed=true} and {@code error=<message>}, and the process goes on; test
 * {@code nodeId.failed} in a gateway condition). Allowed on service, agent, EIP and business-rule tasks.</li>
 * <li>{@code timeout} - ISO-8601 duration limit per attempt.</li>
 * </ul>
 */
public record NodePolicy(int maxAttempts, Duration retryDelay, Duration retryMaxDelay, double retryBackoff,
        boolean continueOnError, Duration timeout) {

    public static final int MAX_RETRIES = 10;
    private static final Set<ElementType> RETRYABLE = Set.of(ElementType.SERVICE_TASK, ElementType.AGENT_TASK,
            ElementType.EIP_TASK);
    private static final Set<ElementType> CAN_CONTINUE = Set.of(ElementType.SERVICE_TASK, ElementType.AGENT_TASK,
            ElementType.EIP_TASK, ElementType.BUSINESS_RULE_TASK);

    /** Parses the policy; throws {@link IllegalArgumentException} describing the first problem. */
    public static NodePolicy of(PlanNode n) {
        List<String> p = problems(n.id(), n.type(), n.config());
        if (!p.isEmpty()) {
            throw new IllegalArgumentException(p.get(0));
        }
        return parse(n.type(), n.config());
    }

    /** Validation messages for an element's policy keys (empty = valid). */
    public static List<String> problems(String id, ElementType type, Map<String, Object> c) {
        List<String> out = new ArrayList<>();
        try {
            if (c.containsKey("retries")) {
                if (!RETRYABLE.contains(type)) {
                    out.add(id + ": 'retries' is only allowed on service, agent and EIP tasks");
                }
                Object r = c.get("retries");
                if (!(r instanceof Number num) || num.intValue() != num.doubleValue() || num.intValue() < 0
                        || num.intValue() > MAX_RETRIES) {
                    out.add(id + ": 'retries' must be an integer 0.." + MAX_RETRIES);
                }
            }
            for (String k : List.of("retryDelay", "retryMaxDelay", "timeout")) {
                if (c.containsKey(k)) {
                    Duration d = duration(id, k, c.get(k));
                    if (d.isNegative() || (k.equals("timeout") && d.isZero())) {
                        out.add(id + ": '" + k + "' must be positive");
                    }
                }
            }
            if (c.containsKey("retryBackoff")) {
                if (!(c.get("retryBackoff") instanceof Number b) || b.doubleValue() < 1.0 || b.doubleValue() > 10.0) {
                    out.add(id + ": 'retryBackoff' must be a number between 1 and 10");
                }
            }
            if (c.containsKey("onError")) {
                if (!"fail".equals(c.get("onError")) && !"continue".equals(c.get("onError"))) {
                    out.add(id + ": 'onError' must be 'fail' or 'continue'");
                } else if ("continue".equals(c.get("onError")) && !CAN_CONTINUE.contains(type)) {
                    out.add(id + ": 'onError: continue' is only allowed on service, agent, EIP and business-rule tasks");
                }
            }
            if (out.isEmpty() && c.containsKey("retryDelay") && c.containsKey("retryMaxDelay")
                    && duration(id, "retryMaxDelay", c.get("retryMaxDelay"))
                            .compareTo(duration(id, "retryDelay", c.get("retryDelay"))) < 0) {
                out.add(id + ": 'retryMaxDelay' must not be shorter than 'retryDelay'");
            }
        } catch (IllegalArgumentException e) {
            out.add(e.getMessage());
        }
        return out;
    }

    private static NodePolicy parse(ElementType type, Map<String, Object> c) {
        int retries = c.get("retries") instanceof Number n ? n.intValue() : 0;
        Duration delay = c.containsKey("retryDelay") ? duration("", "retryDelay", c.get("retryDelay")) : Duration.ofSeconds(1);
        Duration max = c.containsKey("retryMaxDelay") ? duration("", "retryMaxDelay", c.get("retryMaxDelay"))
                : Duration.ofMinutes(5);
        if (max.compareTo(delay) < 0) {
            max = delay; // a short retryDelay with the default cap must stay valid
        }
        double backoff = c.get("retryBackoff") instanceof Number b ? b.doubleValue() : 2.0;
        Duration timeout = c.containsKey("timeout") ? duration("", "timeout", c.get("timeout")) : null;
        return new NodePolicy(retries + 1, delay, max, backoff, "continue".equals(c.get("onError")), timeout);
    }

    private static Duration duration(String id, String key, Object v) {
        try {
            return Duration.parse(String.valueOf(v));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(id + ": '" + key + "' must be an ISO-8601 duration like PT30S but was '" + v + "'");
        }
    }
}
