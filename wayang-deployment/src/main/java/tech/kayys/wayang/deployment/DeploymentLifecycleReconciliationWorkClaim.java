package tech.kayys.wayang.deployment;

import java.util.Objects;

/** Opaque proof of ownership for one reconciliation lease. */
public record DeploymentLifecycleReconciliationWorkClaim(
        DeploymentOperationReceipt receipt, String ownershipToken) {
    public DeploymentLifecycleReconciliationWorkClaim {
        Objects.requireNonNull(receipt, "receipt");
        if (ownershipToken == null || ownershipToken.isBlank()) {
            throw new IllegalArgumentException("ownershipToken must not be blank");
        }
    }
}
