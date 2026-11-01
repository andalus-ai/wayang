package tech.kayys.wayang.api;

import java.time.Instant;

/** One plain (non-secret) setting. {@code scope} is empty for the whole tenant, else a project id. */
public record ConfigEntry(String tenant, String scope, String key, String value, int version, Instant updatedAt, String updatedBy) {
}
