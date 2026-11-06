package tech.kayys.wayang.runtime;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import tech.kayys.wayang.bpmn.ElementType;
import tech.kayys.wayang.bpmn.FlowRuntime;
import tech.kayys.wayang.bpmn.PlanNode;
import tech.kayys.wayang.expression.Expressions;

/**
 * Executor {@code wayang-bpmn}: events and gateways. Start/end/gateways do no
 * work (routing is done by {@link NodeRunner}).
 *
 * <ul>
 * <li>Timer: config {@code duration} (ISO-8601, e.g. {@code PT30M}) or
 * {@code until} (ISO date-time). Parks with a {@code dueAt}; {@link TimerService}
 * fires it.</li>
 * <li>Message catch: config {@code message} (name) and optional
 * {@code correlation} (expression evaluated now against the run variables). A
 * message that arrived earlier is consumed immediately.</li>
 * </ul>
 */
public final class ControlHandler implements DomainHandler {

    private final WaitRegistry waits;
    private final Clock clock;

    public ControlHandler(WaitRegistry waits) {
        this(waits, Clock.systemUTC());
    }

    public ControlHandler(WaitRegistry waits, Clock clock) {
        this.waits = waits;
        this.clock = clock;
    }

    @Override
    public String executorType() {
        return "wayang-bpmn";
    }

    @Override
    public NodeOutcome handle(String runId, PlanNode node, Map<String, Object> vars) {
        ElementType t = node.type();
        if (t == ElementType.TIMER_EVENT) {
            Optional<WaitRegistry.Registered> existing = waits.findByNode(runId, node.id());
            if (existing.isPresent()) {
                return new NodeOutcome.Waiting(existing.get().ticket());
            }
            Instant due = dueAt(node);
            WaitTicket ticket = new WaitTicket(UUID.randomUUID().toString(), node.id(), "timer", node.config(), due, null, null);
            return new NodeOutcome.Waiting(waits.register(runId, ticket));
        }
        if (t == ElementType.MESSAGE_CATCH_EVENT) {
            Object nameCfg = node.config().get("message");
            if (!(nameCfg instanceof String name) || name.isBlank()) {
                return new NodeOutcome.Failed("Message event '" + node.id() + "' needs a 'message' name", false);
            }
            String tenant = vars.get(SecretResolver.TENANT_VARIABLE) instanceof String tv ? tv : "";
            String key = WaitRegistry.scoped(tenant, correlationKey(node, vars));
            Optional<WaitRegistry.Registered> existing = waits.findByNode(runId, node.id());
            if (existing.isPresent()) {
                return new NodeOutcome.Waiting(existing.get().ticket());
            }
            Optional<Map<String, Object>> early = waits.takeBuffered(name, key);
            if (early.isPresent()) {
                return new NodeOutcome.Completed(early.get());
            }
            WaitTicket ticket = new WaitTicket(UUID.randomUUID().toString(), node.id(), "message", node.config(), null, name, key);
            return new NodeOutcome.Waiting(waits.register(runId, ticket));
        }
        return new NodeOutcome.Completed(Map.of());
    }

    private Instant dueAt(PlanNode node) {
        Map<String, Object> c = node.config();
        if (c.get("duration") instanceof String d) {
            return clock.instant().plus(Duration.parse(d));
        }
        if (c.get("until") instanceof String u) {
            return OffsetDateTime.parse(u).toInstant();
        }
        throw new IllegalArgumentException("Timer '" + node.id() + "' needs 'duration' or 'until'");
    }

    private static String correlationKey(PlanNode node, Map<String, Object> vars) {
        if (node.config().get("correlation") instanceof String expr && !expr.isBlank()) {
            Object v = Expressions.parse(expr).evaluate(FlowRuntime.scopeOf(vars));
            if (v == null) {
                throw new IllegalArgumentException("Correlation expression '" + expr + "' of '" + node.id() + "' is null");
            }
            return tech.kayys.wayang.expression.Values.asString(v);
        }
        return "";
    }
}
