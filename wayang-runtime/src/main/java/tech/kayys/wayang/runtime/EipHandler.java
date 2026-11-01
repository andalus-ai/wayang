package tech.kayys.wayang.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tech.kayys.wayang.bpmn.FlowRuntime;
import tech.kayys.wayang.bpmn.PlanNode;
import tech.kayys.wayang.eip.EipException;
import tech.kayys.wayang.eip.EipLoader;
import tech.kayys.wayang.eip.IdempotencyStore;
import tech.kayys.wayang.eip.Message;
import tech.kayys.wayang.eip.Pipeline;
import tech.kayys.wayang.expression.Expressions;

/**
 * Executor {@code wayang-eip}. Config: {@code pipeline} (see {@link EipLoader}),
 * {@code message} (expression for the input body; default {@code input}).
 * Outputs: {@code messages} (list of {headers, body}) and {@code count}.
 */
public final class EipHandler implements DomainHandler {

    private final tech.kayys.wayang.eip.Services base;

    public EipHandler(IdempotencyStore store) {
        this(store, Map.of());
    }

    /** With connectors, pipelines may use {@code call} steps (enrich / wire tap) against them. */
    public EipHandler(IdempotencyStore store, Map<String, ServiceHandler.Connector> connectors) {
        this(store, connectors, new tech.kayys.wayang.eip.ClaimStore.InMemory(10_000),
                new tech.kayys.wayang.eip.AggregationStore.InMemory(10_000), new tech.kayys.wayang.eip.EipState());
    }

    /**
     * Full wiring. {@code claims}, {@code aggregation} and {@code state} must be created once and shared by every run:
     * claim tokens, aggregation groups, circuit breakers and rate limits live in them, not in the (per-execution) pipeline.
     */
    public EipHandler(IdempotencyStore store, Map<String, ServiceHandler.Connector> connectors,
            tech.kayys.wayang.eip.ClaimStore claims, tech.kayys.wayang.eip.AggregationStore aggregation,
            tech.kayys.wayang.eip.EipState state) {
        Map<String, ServiceHandler.Connector> cs = Map.copyOf(connectors);
        tech.kayys.wayang.eip.Invoker invoker = cs.isEmpty() ? null : (name, cfg, in) -> {
            ServiceHandler.Connector c = cs.get(name);
            if (c == null) {
                throw new tech.kayys.wayang.eip.Invoker.Permanent("Unknown connector '" + name + "'");
            }
            try {
                return c.call(cfg, in);
            } catch (ServiceHandler.ConnectorException e) {
                if (!e.retryable()) {
                    throw new tech.kayys.wayang.eip.Invoker.Permanent(e.getMessage());
                }
                throw e;
            }
        };
        this.base = new tech.kayys.wayang.eip.Services(store, invoker, claims, aggregation, state, "", null, null);
    }

    @Override
    public String executorType() {
        return "wayang-eip";
    }

    @Override
    public NodeOutcome handle(String runId, PlanNode node, Map<String, Object> vars) {
        if (!(node.config().get("pipeline") instanceof Map<?, ?> cfg)) {
            return new NodeOutcome.Failed("Node " + node.id() + " needs a 'pipeline' configuration", false);
        }
        try {
            Map<String, Object> c = new LinkedHashMap<>();
            cfg.forEach((k, v) -> c.put(String.valueOf(k), v));
            String tenant = vars.get(SecretResolver.TENANT_VARIABLE) instanceof String t ? t : "";
            String process = vars.get(SecretResolver.PROCESS_VARIABLE) instanceof String pr ? pr : "";
            // keys, claims, groups and breakers are private to tenant + process + node: no cross-tenant collisions or reads
            String ns = tenant + "|" + process + "|" + node.id();
            IdempotencyStore shared = base.idempotency();
            tech.kayys.wayang.eip.Services svc = base.withNamespace(ns)
                    .withIdempotency(shared == null ? null : key -> shared.markIfNew(ns + '\0' + key));
            Pipeline p = EipLoader.build(c, svc);
            var scope = FlowRuntime.scopeOf(vars);
            String src = node.config().get("message") == null ? "input" : String.valueOf(node.config().get("message"));
            Object body = Expressions.parse(src).evaluate(scope);
            List<Message> out = p.run(Message.of(body), scope);
            List<Object> msgs = new ArrayList<>();
            out.forEach(m -> msgs.add(m.toMap()));
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("messages", msgs);
            o.put("count", out.size());
            return new NodeOutcome.Completed(o);
        } catch (EipException e) {
            return new NodeOutcome.Failed(e.getMessage(), false);
        }
    }
}
