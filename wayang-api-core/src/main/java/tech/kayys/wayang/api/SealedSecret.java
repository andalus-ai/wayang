package tech.kayys.wayang.api;

import java.time.Instant;

/** A stored secret: ciphertext only. {@code sealed} is {@code v1:keyId:base64(iv||ciphertext)}. */
public record SealedSecret(String tenant, String name, int version, String keyId, String sealed, Instant updatedAt,
        String updatedBy) {
}
