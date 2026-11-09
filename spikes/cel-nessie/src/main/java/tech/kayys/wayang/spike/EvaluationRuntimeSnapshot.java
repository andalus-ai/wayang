package tech.kayys.wayang.spike;

import java.util.Objects;

/**
 * Combined, aggregate-only view of admission and provider-executor pressure.
 * The two component snapshots are sampled sequentially and are not atomic.
 */
public record EvaluationRuntimeSnapshot(
        int admittedActive,
        int admissionQueued,
        boolean admissionClosed,
        int activeWorkers,
        int queuedProviderTasks,
        boolean executorShutdown,
        boolean executorTerminated) {

    public EvaluationRuntimeSnapshot {
        if (admittedActive < 0 || admissionQueued < 0 || activeWorkers < 0 || queuedProviderTasks < 0) {
            throw new IllegalArgumentException("Runtime pressure counts must not be negative");
        }
    }

    public static EvaluationRuntimeSnapshot capture(
            EvaluationAdmissionController admission,
            AdmittedEvaluationCoordinator coordinator) {
        Objects.requireNonNull(admission, "admission");
        Objects.requireNonNull(coordinator, "coordinator");
        EvaluationAdmissionController.Snapshot admissionSnapshot = admission.snapshot();
        return new EvaluationRuntimeSnapshot(
                admissionSnapshot.active(),
                admissionSnapshot.queued(),
                admissionSnapshot.closed(),
                coordinator.activeWorkers(),
                coordinator.queuedTasks(),
                coordinator.isShutdown(),
                coordinator.isTerminated());
    }
}
