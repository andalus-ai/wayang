package tech.kayys.wayang.rest.management;

import java.util.Set;

/**
 * Explicit allow-list SPI for management commands.
 *
 * <p>Delivery is at-least-once. Implementations MUST persist deduplication by commandId and
 * idempotencyKey across process restarts, and return success only after the operation is complete.
 * For APPLY_DEPLOYMENT, verifiedArtifactDigest must be the independently computed digest of the
 * artifact actually installed, not a copy of the requested digest.</p>
 */
public interface RuntimeManagementCommandHandler {
    Set<String> commandTypes();
    CommandResult execute(Command command) throws Exception;

    record Command(String commandId, String idempotencyKey, String commandType, String typedPayload) {}
    record CommandResult(boolean successful, String message, String verifiedArtifactDigest) {}
}
