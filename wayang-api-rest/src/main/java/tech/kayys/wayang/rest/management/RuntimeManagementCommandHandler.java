package tech.kayys.wayang.rest.management;

import java.util.Set;

/**
 * Explicit allow-list SPI for management commands. A handler must validate payloads and provide
 * idempotent execution keyed by commandId/idempotencyKey. No handler is registered by default.
 */
public interface RuntimeManagementCommandHandler {
    Set<String> commandTypes();
    CommandResult execute(Command command) throws Exception;

    record Command(String commandId, String idempotencyKey, String commandType, String typedPayload) {}
    record CommandResult(boolean successful, String message, String verifiedArtifactDigest) {}
}
