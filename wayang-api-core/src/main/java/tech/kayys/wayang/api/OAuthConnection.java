package tech.kayys.wayang.api;

import java.time.Instant;
import java.util.List;

/** One authorised account of a provider for a tenant. Holds no tokens (those live in the secret store). */
public record OAuthConnection(String tenant, String id, String provider, String clientId, String clientSecretName,
        List<String> scopes, Status status, String createdBy, Instant createdAt, Instant connectedAt) {

    public enum Status { PENDING, CONNECTED, REVOKED }

    public OAuthConnection {
        scopes = List.copyOf(scopes);
    }

    public OAuthConnection with(Status s, Instant connected) {
        return new OAuthConnection(tenant, id, provider, clientId, clientSecretName, scopes, s, createdBy, createdAt, connected);
    }
}
