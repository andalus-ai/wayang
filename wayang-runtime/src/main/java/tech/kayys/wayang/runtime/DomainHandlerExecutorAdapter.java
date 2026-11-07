package tech.kayys.wayang.runtime;

import java.util.Map;
import java.util.Objects;
import tech.kayys.wayang.bpmn.PlanNode;

/**
 * Compatibility bridge exposing an existing BPMN-aware {@link DomainHandler}
 * through the neutral {@link ComponentExecutor} contract.
 *
 * <p>This adapter intentionally stays in the existing runtime module because
 * DomainHandler and PlanNode are BPMN-facing APIs. It is the migration seam:
 * callers can adopt ComponentExecutor without forcing existing handlers to
 * change. The adapter does not own retry, timeout, or persistence policy.</p>
 */
public final class DomainHandlerExecutorAdapter implements ComponentExecutor {

    private final DomainHandler delegate;
    private final String componentId;
    private final String componentVersion;

    public DomainHandlerExecutorAdapter(DomainHandler delegate, String componentId, String componentVersion) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.componentId = requireText(componentId, "componentId");
        this.componentVersion = requireText(componentVersion, "componentVersion");
    }

    @Override
    public String executorId() {
        return delegate.executorType();
    }

    @Override
    public ComponentExecutionResult execute(ExecutionRequest request) {
        Objects.requireNonNull(request, "request");
        if (!componentId.equals(request.componentId())) {
            return new ComponentExecutionResult.Rejected("COMPONENT_MISMATCH",
                    "Adapter for '" + componentId + "' cannot execute '" + request.componentId() + "'");
        }
        if (!componentVersion.equals(request.componentVersion())) {
            return new ComponentExecutionResult.Rejected("VERSION_MISMATCH",
                    "Adapter version '" + componentVersion + "' cannot execute requested version '"
                            + request.componentVersion() + "'");
        }

        PlanNode node;
        try {
            node = planNode(request);
        } catch (IllegalArgumentException | ClassCastException ex) {
            return new ComponentExecutionResult.Rejected("INVALID_PLAN_NODE", ex.getMessage());
        }

        NodeOutcome outcome = delegate.handle(request.runId(), node, request.inputs());
        if (outcome instanceof NodeOutcome.Completed completed) {
            return new ComponentExecutionResult.Completed(completed.outputs());
        }
        if (outcome instanceof NodeOutcome.Waiting waiting) {
            WaitTicket ticket = waiting.ticket();
            return new ComponentExecutionResult.Suspended(ticket.token(),
                    Map.of("nodeId", ticket.nodeId(), "kind", ticket.kind(), "info", ticket.info()));
        }
        if (outcome instanceof NodeOutcome.Failed failed) {
            return new ComponentExecutionResult.Failed(failed.error(), failed.retryable());
        }
        return new ComponentExecutionResult.Failed("Unsupported DomainHandler outcome: "
                + outcome.getClass().getName(), false);
    }

    private PlanNode planNode(ExecutionRequest request) {
        Object raw = request.configuration().get("wayang.planNode");
        if (raw instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, ?> nodeConfig = (Map<String, ?>) map;
            return PlanNode.fromConfiguration(nodeConfig);
        }
        Object elementConfig = request.configuration().get("wayang.element.config");
        if (!(elementConfig instanceof Map<?, ?> config)) {
            throw new IllegalArgumentException("configuration must contain wayang.planNode or wayang.element.config");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> configMap = (Map<String, Object>) config;
        Object nodeId = request.configuration().get("wayang.element.id");
        Object nodeType = request.configuration().get("wayang.element.type");
        if (nodeId == null || nodeType == null) {
            throw new IllegalArgumentException("missing wayang.element.id or wayang.element.type");
        }
        Map<String, Object> nodeConfig = new java.util.LinkedHashMap<>(request.configuration());
        nodeConfig.put("wayang.element.config", configMap);
        return PlanNode.fromConfiguration(nodeConfig);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
