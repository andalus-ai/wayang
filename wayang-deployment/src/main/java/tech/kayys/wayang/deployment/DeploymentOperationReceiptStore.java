package tech.kayys.wayang.deployment;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Atomic persistence boundary for deployment operation idempotency and recovery. */
public interface DeploymentOperationReceiptStore {
    /**
     * Legacy replaying claim. Returns the existing receipt for a duplicate key and must not dispatch it.
     */
    DeploymentOperationReceipt claim(String deploymentId, String operationKey, String fingerprint,
            String operation, String targetOperationKey, Instant now, Instant leaseUntil);

    /**
     * Atomically claims a new operation. Implementations must guarantee exactly one acquired=true
     * result across concurrent callers. The default deliberately fails closed: stores must implement
     * the atomic primitive before being used by a receipt-aware dispatcher.
     */
    default DeploymentOperationClaim claimExclusive(String deploymentId, String operationKey, String fingerprint,
            String operation, String targetOperationKey, Instant now, Instant leaseUntil) {
        throw new UnsupportedOperationException("This receipt store does not implement atomic exclusive claims");
    }

    DeploymentOperationReceipt complete(String deploymentId, String operationKey, String fingerprint,
            DeploymentOperationStatus terminalStatus, String resultCode, String resultDetail, Instant now);

    Optional<DeploymentOperationReceipt> find(String deploymentId, String operationKey);

    List<DeploymentOperationReceipt> findExpiredRunning(Instant now, int limit);

    DeploymentOperationReceipt markUnknown(String deploymentId, String operationKey, String reason, Instant now);
}
