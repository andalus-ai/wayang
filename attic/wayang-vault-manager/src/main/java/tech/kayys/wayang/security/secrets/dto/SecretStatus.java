package tech.kayys.wayang.security.secrets.dto;

/**
 * Enumeration of secret statuses in the lifecycle.
 */
public enum SecretStatus {
    /**
     * Secret is currently active and usable
     */
    ACTIVE,

    /**
     * Secret has been deprecated (new version exists)
     */
    DEPRECATED,

    /**
     * Secret has been deleted (soft delete - recoverable)
     */
    DELETED,

    /**
     * Secret has reached its TTL expiration
     */
    EXPIRED,

    /**
     * Secret has been rotated to a new version
     */
    ROTATED
}
