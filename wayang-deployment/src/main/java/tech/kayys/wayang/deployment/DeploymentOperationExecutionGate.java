package tech.kayys.wayang.deployment;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Durable at-most-once dispatch gate for one deployment side effect.
 *
 * The exclusive receipt is committed before the supplied operation is invoked. Only the winner
 * invokes the supplier. Existing RUNNING, terminal, or UNKNOWN receipts are returned as replays.
 * Exceptions from the operation or result classifier make the receipt UNKNOWN; they are never
 * interpreted as proof that the target did not perform the side effect.
 *
 * This gate deliberately does not own lifecycle transitions. Callers must keep lifecycle updates
 * and receipt reconciliation coordinated and must not claim cross-database exactly-once semantics.
 */
public final class DeploymentOperationExecutionGate {
    private final DeploymentOperationReceiptService receipts;

    public DeploymentOperationExecutionGate(DeploymentOperationReceiptService receipts) {
        this.receipts = Objects.requireNonNull(receipts, "receipts");
    }

    public <T> DeploymentOperationExecutionResult<T> execute(
            String operation,
            DeploymentCommand command,
            Duration lease,
            Supplier<T> dispatch,
            Function<T, DeploymentOperationStatus> statusClassifier,
            Function<T, String> resultCode,
            Function<T, String> resultDetail) {
        return execute(operation, command, lease, () -> { }, dispatch, statusClassifier, resultCode, resultDetail);
    }

    /**
     * Variant that commits a local pre-dispatch transition after the exclusive claim and before
     * invoking the external target. The callback must not perform the external side effect.
     */
    public <T> DeploymentOperationExecutionResult<T> execute(
            String operation,
            DeploymentCommand command,
            Duration lease,
            Runnable beforeDispatch,
            Supplier<T> dispatch,
            Function<T, DeploymentOperationStatus> statusClassifier,
            Function<T, String> resultCode,
            Function<T, String> resultDetail) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(beforeDispatch, "beforeDispatch");
        Objects.requireNonNull(dispatch, "dispatch");
        Objects.requireNonNull(statusClassifier, "statusClassifier");
        Objects.requireNonNull(resultCode, "resultCode");
        Objects.requireNonNull(resultDetail, "resultDetail");

        DeploymentOperationClaim claim = receipts.claimExclusive(operation, command, lease);
        if (!claim.acquired()) {
            return new DeploymentOperationExecutionResult<>(false, null, claim.receipt());
        }

        try {
            beforeDispatch.run();
        } catch (RuntimeException failure) {
            // The target has not been invoked. Record a terminal local rejection, then propagate.
            receipts.complete(operation, command, DeploymentOperationStatus.FAILED,
                    "PRE_DISPATCH_REJECTED", failure.getClass().getSimpleName());
            throw failure;
        }

        final T result;
        final DeploymentOperationStatus status;
        final String code;
        final String detail;
        try {
            result = Objects.requireNonNull(dispatch.get(), "dispatch result");
            status = Objects.requireNonNull(statusClassifier.apply(result), "classified status");
            if (status != DeploymentOperationStatus.SUCCEEDED && status != DeploymentOperationStatus.FAILED) {
                throw new IllegalArgumentException("dispatch result must classify as SUCCEEDED or FAILED");
            }
            code = resultCode.apply(result);
            detail = resultDetail.apply(result);
        } catch (RuntimeException failure) {
            // A thrown adapter call or classifier may have happened after the remote side effect.
            // Mark uncertainty and propagate; never retry implicitly.
            receipts.markUnknown(command, "Dispatch outcome is uncertain: " + failure.getClass().getSimpleName());
            throw failure;
        }

        // Keep completion outside the catch above. If persistence fails after dispatch, leave RUNNING
        // for the observation/recovery worker instead of masking the storage failure as a known result.
        DeploymentOperationReceipt completed = receipts.complete(operation, command, status, code, detail);
        return new DeploymentOperationExecutionResult<>(true, result, completed);
    }
}
