package tech.kayys.wayang.deployment;

/** Bounded summary of one recovery scan; counts are observations, not deployment state changes. */
public record DeploymentOperationRecoveryReport(
        int scanned, int succeeded, int failed, int stillRunning, int unknown, int conflicted) {
    public DeploymentOperationRecoveryReport {
        if (scanned < 0 || succeeded < 0 || failed < 0 || stillRunning < 0 || unknown < 0 || conflicted < 0) {
            throw new IllegalArgumentException("recovery counts must not be negative");
        }
        if (succeeded + failed + stillRunning + unknown + conflicted > scanned) {
            throw new IllegalArgumentException("recovery outcomes cannot exceed scanned count");
        }
    }
}
