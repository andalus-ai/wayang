package tech.kayys.wayang.adapter.gamelan;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.smallrye.mutiny.subscription.UniEmitter;
import tech.kayys.gamelan.engine.node.NodeExecutionResult;
import tech.kayys.wayang.bpmn.PlanNode;

/**
 * In-process parking lot for nodes that wait for people, timers or messages.
 *
 * <p>
 * <strong>Limitation (unverified against a live engine):</strong> Gamelan only
 * completes a node from a COMPLETED result, so a parked node is represented as
 * an executor call whose result is delivered later. That ties the wait to this
 * JVM: after a restart the engine would have to re-dispatch the node (it then
 * creates a fresh wait). It also relies on the engine/executor transport not
 * timing out long-running calls; set the node {@code timeout} generously.
 */
final class PendingWaits {

    record Pending(PlanNode node, Map<String, Object> vars, UniEmitter<? super NodeExecutionResult> emitter,
            java.util.function.Function<Map<String, Object>, NodeExecutionResult> onComplete) {
    }

    private final Map<String, Pending> byToken = new ConcurrentHashMap<>();

    void park(String token, Pending p) {
        byToken.put(token, p);
    }

    /** @return false when the token is unknown (already completed, or lost on restart) */
    boolean complete(String token, Map<String, Object> outputs) {
        Pending p = byToken.remove(token);
        if (p == null) {
            return false;
        }
        p.emitter().complete(p.onComplete().apply(outputs));
        return true;
    }

    /** Fails a parked node, e.g. when a human task is rejected or a timer is cancelled. */
    boolean cancel(String token, Throwable reason) {
        Pending p = byToken.remove(token);
        if (p == null) {
            return false;
        }
        p.emitter().fail(reason);
        return true;
    }

    int size() {
        return byToken.size();
    }
}
