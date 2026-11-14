package tech.kayys.wayang.deployment;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Atomic persistence boundary for deployment operation idempotency and recovery. */
public interface DeploymentOperationReceiptStore {
    /**
     * Atomically creates and starts an operation, or returns the existing receipt.
     * A RUNNING receipt whose lease has expired is changed to UNKNOWN, never re-executed implicitly.
     */
    DeploymentOperationReceipt claim(String deploymentId, String operationKey, String fingerprint,
            String operation, String targetOperationKey, Instant now, Instant leaseUntil);

    DeploymentOperationReceipt complete(String deploymentId, String operationKey, String fingerprint,
            DeploymentOperationStatus terminalStatus, String resultCode, String resultDetail, Instant now);

    Optional<DeploymentOperationReceipt> find(String deploymentId, String operationKey);

    List<DeploymentOperationReceipt> findExpiredRunning(Instant now, int limit);

    DeploymentOperationReceipt markUnknown(String deploymentId, String operationKey, String reason, Instant now);
}
