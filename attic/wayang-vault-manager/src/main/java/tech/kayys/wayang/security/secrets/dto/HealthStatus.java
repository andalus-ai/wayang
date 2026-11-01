package tech.kayys.wayang.security.secrets.dto;

import java.util.Map;
import java.util.Optional;

/**
 * Health status report for the secret backend.
 */
public record HealthStatus(
    boolean healthy,
    String backend,
    Map<String, Object> details,
    Optional<String> error
) {
    public HealthStatus {
        if (backend == null || backend.isBlank()) {
            throw new IllegalArgumentException("backend cannot be empty");
        }
        details = details != null ? Map.copyOf(details) : Map.of();
        if (error == null) {
            error = Optional.empty();
        }
    }

    /**
     * Create a healthy status response
     */
    public static HealthStatus healthy(String backend) {
        return new HealthStatus(true, backend, Map.of(), Optional.empty());
    }

    /**
     * Create a healthy status with details
     */
    public static HealthStatus healthy(String backend, Map<String, Object> details) {
        return new HealthStatus(true, backend, details, Optional.empty());
    }

    /**
     * Create an unhealthy status response
     */
    public static HealthStatus unhealthy(String backend, String error) {
        return new HealthStatus(false, backend, Map.of(), Optional.of(error));
    }

    /**
     * Create an unhealthy status with details
     */
    public static HealthStatus unhealthy(String backend, String error, Map<String, Object> details) {
        return new HealthStatus(false, backend, details, Optional.of(error));
    }
}
