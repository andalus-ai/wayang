package tech.kayys.wayang.api;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import tech.kayys.wayang.bpmn.ExecutionPlan;

/**
 * Port to whatever executes runs. {@link ReferenceRunEngine} is an in-memory
 * implementation for development and tests; production wiring delegates to
 * Gamelan (not implemented in this module). Every method is tenant-scoped:
 * an engine must never return or modify another tenant's run.
 */
public interface RunEngine {

    RunView start(String tenantId, String userId, String processId, int version, ExecutionPlan plan,
            Map<String, Object> input);

    Optional<RunView> get(String tenantId, String runId);

    /** Newest first. {@code processId} may be null (all processes). */
    List<RunView> list(String tenantId, String processId, int limit);

    /** @return false when no such wait exists for this tenant (already completed, or unknown) */
    boolean completeWait(String tenantId, String token, Map<String, Object> outputs);

    boolean cancel(String tenantId, String runId);

    /** Completes every due timer wait, across tenants; called by a scheduler or an admin. @return timers fired */
    int fireDueTimers();

    /** Gives engines without native event streams a chance to announce progress; called periodically. */
    default void poll() {
    }
}
