package tech.kayys.wayang.runtime;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import tech.kayys.wayang.bpmn.FlowRuntime;
import tech.kayys.wayang.bpmn.PlanNode;
import tech.kayys.wayang.dmn.DecisionModel;
import tech.kayys.wayang.dmn.DmnException;
import tech.kayys.wayang.expression.Expressions;

/**
 * Executor {@code wayang-dmn}. Config: {@code model} (id of a registered
 * {@link DecisionModel}), optional {@code decisions} (list of decision names to
 * evaluate), optional {@code inputs} (map of name to expression; default is the
 * whole variable space). Outputs: every evaluated decision under its name, and
 * {@code result} = the last decision in evaluation order.
 */
public final class DmnHandler implements DomainHandler {

    private final java.util.function.Function<String, DecisionModel> models;

    public DmnHandler(Map<String, DecisionModel> models) {
        Map<String, DecisionModel> copy = Map.copyOf(models);
        this.models = copy::get;
    }

    /** Resolves models on demand (e.g. tenant-qualified ids from a definition store); may return null. */
    public DmnHandler(java.util.function.Function<String, DecisionModel> lookup) {
        this.models = lookup;
    }

    @Override
    public String executorType() {
        return "wayang-dmn";
    }

    @Override
    public NodeOutcome handle(String runId, PlanNode node, Map<String, Object> vars) {
        Object modelId = node.config().get("model");
        DecisionModel model = modelId == null ? null : models.apply(String.valueOf(modelId));
        if (model == null) {
            return new NodeOutcome.Failed("Unknown decision model '" + modelId + "' in node " + node.id(), false);
        }
        Map<String, Object> inputs = FlowRuntime.nest(vars);
        if (node.config().get("inputs") instanceof Map<?, ?> m) {
            var scope = FlowRuntime.scopeOf(vars);
            Map<String, Object> mapped = new LinkedHashMap<>();
            m.forEach((k, v) -> mapped.put(String.valueOf(k), Expressions.parse(String.valueOf(v)).evaluate(scope)));
            inputs = mapped;
        }
        Set<String> wanted = Set.of();
        if (node.config().get("decisions") instanceof java.util.List<?> l) {
            wanted = new java.util.LinkedHashSet<>();
            for (Object o : l) {
                wanted.add(String.valueOf(o));
            }
        }
        try {
            Map<String, Object> results = model.evaluate(inputs, wanted);
            Map<String, Object> out = new LinkedHashMap<>(results);
            Object last = null;
            for (Object v : results.values()) {
                last = v;
            }
            out.put("result", last);
            return new NodeOutcome.Completed(out);
        } catch (DmnException e) {
            return new NodeOutcome.Failed(e.getMessage(), false);
        }
    }
}
