package tech.kayys.wayang.management.client;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** JSON DTOs matching the implemented Wayang Console REST management API v1. */
public final class ManagementContracts {
    private ManagementContracts() {}

    public record EnrollRequest(String enrollmentToken, String instanceId, String runtimeVersion,
            String deploymentId, String hostname, String platform) {}
    public record EnrollResponse(String registrationId, String registrationToken,
            Instant registrationExpiresAt, Instant leaseExpiresAt, int heartbeatIntervalSeconds) {}

    public record HeartbeatRequest(String health) {}
    public record HeartbeatResponse(Instant leaseExpiresAt, int heartbeatIntervalSeconds) {}

    public record CapabilityInput(String componentId, String version, String capability) {}
    public record WorkflowInput(String workflowId, String workflowVersion,
            String definitionDigest, String deploymentId) {}
    public record InventoryRequest(List<CapabilityInput> capabilities, List<WorkflowInput> workflows) {
        public InventoryRequest {
            capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
            workflows = workflows == null ? List.of() : List.copyOf(workflows);
        }
    }
    public record InventoryResponse(String revision, int capabilityCount, int workflowCount, Instant reportedAt) {}

    public record PollRequest(Integer limit) {}
    public record CommandView(UUID commandId, String idempotencyKey, String commandType,
            String typedPayload, Instant expiresAt, int attemptCount) {}
    public record CommandPage(List<CommandView> commands) {
        public CommandPage {
            commands = commands == null ? List.of() : List.copyOf(commands);
        }
    }

    public record AckRequest(String status, String message, String verifiedArtifactDigest) {}
    public record AckResponse(UUID commandId, String status, boolean accepted,
            Instant completedAt, String verifiedArtifactDigest) {}
    public record ErrorResponse(String code, String message) {}
}
