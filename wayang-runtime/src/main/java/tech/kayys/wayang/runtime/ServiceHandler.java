package tech.kayys.wayang.runtime;

import java.util.LinkedHashMap;
import java.util.Map;

import tech.kayys.wayang.bpmn.FlowRuntime;
import tech.kayys.wayang.bpmn.PlanNode;
import tech.kayys.wayang.expression.Expressions;

/**
 * Executor {@code wayang-service}: calls a named {@link Connector}. Config:
 * {@code connector}, {@code inputs} (name to expression). This is the seam
 * where HTTP/DB/queue connectors plug in; Wayang ships none by default.
 */
public final class ServiceHandler implements DomainHandler {

    /** A named outbound integration. */
    @FunctionalInterface
    public interface Connector {
        Map<String, Object> call(Map<String, Object> inputs) throws Exception;

        /**
         * Connector-aware variant: {@code config} is the node's static configuration without the keys
         * {@code connector}, {@code inputs} and the retry/timeout/onError policy keys (secrets are already resolved).
         * Defaults to the inputs-only form so existing lambdas keep working.
         */
        default Map<String, Object> call(Map<String, Object> config, Map<String, Object> inputs) throws Exception {
            return call(inputs);
        }
    }

    /** Thrown by connectors to say whether trying again can help (e.g. 503 yes, 404 no). */
    public static final class ConnectorException extends Exception {
        private static final long serialVersionUID = 1L;
        private final boolean retryable;

        public ConnectorException(String message, boolean retryable) {
            super(message);
            this.retryable = retryable;
        }

        public boolean retryable() {
            return retryable;
        }
    }

    private static final java.util.Set<String> POLICY_KEYS = java.util.Set.of("connector", "inputs", "retries",
            "retryDelay", "retryMaxDelay", "retryBackoff", "timeout", "onError", "inputMapping", "outputMapping",
            "outputMode", "omitNull");

    private final Map<String, Connector> connectors;

    public ServiceHandler(Map<String, Connector> connectors) {
        this.connectors = Map.copyOf(connectors);
    }

    @Override
    public String executorType() {
        return "wayang-service";
    }

    @Override
    public NodeOutcome handle(String runId, PlanNode node, Map<String, Object> vars) {
        Object name = node.config().get("connector");
        Connector c = name == null ? null : connectors.get(String.valueOf(name));
        if (c == null) {
            return new NodeOutcome.Failed("Unknown connector '" + name + "' in node " + node.id(), false);
        }
        var scope = FlowRuntime.scopeOf(vars);
        Map<String, Object> in = new LinkedHashMap<>();
        if (node.config().get("inputs") instanceof Map<?, ?> m) {
            m.forEach((k, v) -> in.put(String.valueOf(k), Expressions.parse(String.valueOf(v)).evaluate(scope)));
        }
        try {
            boolean omit = Boolean.parseBoolean(String.valueOf(node.config().get("omitNull")));
            if (node.config().get("inputMapping") != null) {
                // Mapper output overrides same-named flat inputs; it must produce an object.
                Object mapped = tech.kayys.wayang.eip.Mapper.compile(node.config().get("inputMapping"), omit).apply(scope);
                if (!(mapped instanceof Map<?, ?> mm)) {
                    return new NodeOutcome.Failed("inputMapping of node " + node.id() + " must produce an object", false);
                }
                mm.forEach((k, v) -> in.put(String.valueOf(k), v));
            }
            Map<String, Object> cfg = new LinkedHashMap<>();
            node.config().forEach((k, v) -> {
                if (!POLICY_KEYS.contains(k)) {
                    cfg.put(k, v);
                }
            });
            Map<String, Object> out = c.call(cfg, in);
            out = out == null ? Map.of() : out;
            if (node.config().get("outputMapping") != null) {
                // Evaluated with the process variables plus `result` (the raw connector output).
                Object mapped = tech.kayys.wayang.eip.Mapper.compile(node.config().get("outputMapping"), omit)
                        .apply(scope.child("result", out));
                if (!(mapped instanceof Map<?, ?> mm)) {
                    return new NodeOutcome.Failed("outputMapping of node " + node.id() + " must produce an object", false);
                }
                Map<String, Object> res = new LinkedHashMap<>();
                if ("merge".equals(String.valueOf(node.config().get("outputMode")))) {
                    res.putAll(out);
                }
                mm.forEach((k, v) -> res.put(String.valueOf(k), v));
                out = res;
            }
            return new NodeOutcome.Completed(out);
        } catch (tech.kayys.wayang.eip.EipException e) {
            return new NodeOutcome.Failed("Mapping failed in node " + node.id() + ": " + e.getMessage(), false);
        } catch (ConnectorException e) {
            return new NodeOutcome.Failed("Connector '" + name + "' failed: " + e.getMessage(), e.retryable());
        } catch (Exception e) {
            return new NodeOutcome.Failed("Connector '" + name + "' failed: " + e.getMessage(), true);
        }
    }
}
