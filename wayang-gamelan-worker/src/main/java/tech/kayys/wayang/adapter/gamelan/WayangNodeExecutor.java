package tech.kayys.wayang.adapter.gamelan;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import tech.kayys.gamelan.engine.error.ErrorInfo;
import tech.kayys.gamelan.engine.node.NodeExecutionResult;
import tech.kayys.gamelan.engine.node.NodeExecutionTask;
import tech.kayys.gamelan.sdk.executor.core.AbstractWorkflowExecutor;
import tech.kayys.gamelan.sdk.executor.core.SimpleNodeExecutionResult;
import tech.kayys.wayang.bpmn.PlanNode;
import tech.kayys.wayang.runtime.NodeOutcome;

/**
 * Base class of the Wayang executors. One concrete subclass per Gamelan
 * executor type (the {@code @Executor} annotation is per class), all sharing
 * this logic: rebuild the {@link PlanNode} from the node configuration, run it
 * through {@link tech.kayys.wayang.runtime.NodeRunner}, translate the outcome.
 */
public abstract class WayangNodeExecutor extends AbstractWorkflowExecutor {

    @Inject
    WayangRuntime runtime;

    @Override
    public Uni<NodeExecutionResult> execute(NodeExecutionTask task) {
        final Instant started = Instant.now();
        final PlanNode node;
        final Map<String, Object> vars;
        try {
            node = PlanNode.fromConfiguration(task.nodeConfiguration());
            vars = task.workflowVariables();
        } catch (RuntimeException e) {
            return Uni.createFrom().item(failure(task, "WAYANG_BAD_NODE", e.getMessage()));
        }
        // Handlers may block (connectors, model calls); keep them off the event loop.
        return Uni.createFrom().item(() -> runtime.runner().run(task.runId().value(), node, vars, task.attempt()))
                .runSubscriptionOn(io.smallrye.mutiny.infrastructure.Infrastructure.getDefaultWorkerPool())
                .flatMap(outcome -> translate(task, node, vars, outcome, started));
    }

    private Uni<NodeExecutionResult> translate(NodeExecutionTask task, PlanNode node, Map<String, Object> vars,
            NodeOutcome outcome, Instant started) {
        if (outcome instanceof NodeOutcome.Completed c) {
            return Uni.createFrom().item(success(task, c.outputs(), started));
        }
        if (outcome instanceof NodeOutcome.Failed f) {
            return Uni.createFrom().item(failure(task, f.retryable() ? "WAYANG_RETRYABLE" : "WAYANG_FAILED", f.error()));
        }
        NodeOutcome.Waiting w = (NodeOutcome.Waiting) outcome;
        return Uni.createFrom().emitter(emitter -> runtime.waits().park(w.ticket().token(),
                new PendingWaits.Pending(node, vars, emitter, outputs -> {
                    NodeOutcome resumed = runtime.runner().resume(node, vars, outputs);
                    if (resumed instanceof NodeOutcome.Completed rc) {
                        return success(task, rc.outputs(), started);
                    }
                    return failure(task, "WAYANG_FAILED", ((NodeOutcome.Failed) resumed).error());
                })));
    }

    private static NodeExecutionResult success(NodeExecutionTask task, Map<String, Object> out, Instant started) {
        return SimpleNodeExecutionResult.success(task.runId(), task.nodeId(), task.attempt(), out, task.token(),
                Duration.between(started, Instant.now()));
    }

    private static NodeExecutionResult failure(NodeExecutionTask task, String code, String message) {
        return SimpleNodeExecutionResult.failure(task.runId(), task.nodeId(), task.attempt(),
                new ErrorInfo(code, message, null, Map.of()), task.token());
    }
}
