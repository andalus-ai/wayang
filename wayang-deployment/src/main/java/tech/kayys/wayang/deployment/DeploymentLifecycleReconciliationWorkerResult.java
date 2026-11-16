package tech.kayys.wayang.deployment;

/** Bounded batch outcome from one reconciliation worker tick. */
public record DeploymentLifecycleReconciliationWorkerResult(
        int scanned,
        int claimed,
        int reconciled,
        int waiting,
        int unresolved,
        int conflicts,
        int skipped) {
    public DeploymentLifecycleReconciliationWorkerResult {
        if (scanned < 0 || claimed < 0 || reconciled < 0 || waiting < 0
                || unresolved < 0 || conflicts < 0 || skipped < 0) {
            throw new IllegalArgumentException("worker counts must not be negative");
        }
    }
}
