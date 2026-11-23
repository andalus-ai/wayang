package tech.kayys.wayang.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.EnumSet;

/**
 * Authentication for the Community all-in-one server ({@code wayang.auth.mode=local}): one operator key, one tenant, no identity
 * service. The caller sends {@code Authorization: Bearer <key>}; a match is a full administrator of the configured tenant (default
 * {@code local}). It is meant for a developer's machine or a private network, never for a shared deployment: use {@code jwt} or
 * {@code numu} there. The key must be long and random (at least 24 characters) and is never echoed in an error.
 */
public final class LocalAuth {

    public static final int MIN_KEY_LENGTH = 24;

    private final byte[] keyDigest;
    private final String tenant;

    /** @throws IllegalArgumentException (safe to show, no key content) when the key is missing or too short, or the tenant is invalid */
    public LocalAuth(String apiKey, String tenant) {
        if (apiKey == null || apiKey.length() < MIN_KEY_LENGTH) {
            throw new IllegalArgumentException("wayang.auth.local.api-key must be at least " + MIN_KEY_LENGTH + " characters");
        }
        this.keyDigest = sha256(apiKey);
        this.tenant = tenant == null || tenant.isBlank() ? "local" : tenant.trim();
        new Principal(this.tenant, "local", EnumSet.noneOf(Role.class)); // validates the tenant id
    }

    /** @param authorization the raw {@code Authorization} header value */
    public Principal resolve(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw new ApiException(ApiException.Code.UNAUTHORIZED, "Not authenticated");
        }
        // compare digests so the comparison time does not depend on how much of the key matched
        if (!MessageDigest.isEqual(keyDigest, sha256(authorization.substring(7).trim()))) {
            throw new ApiException(ApiException.Code.UNAUTHORIZED, "Not authenticated");
        }
        return new Principal(tenant, "local", EnumSet.of(Role.ADMIN));
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
