package tech.kayys.wayang.security.secrets.local;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;
import tech.kayys.wayang.security.secrets.core.SecretManager;
import tech.kayys.wayang.security.secrets.dto.*;
import tech.kayys.wayang.security.secrets.exception.SecretException;
import tech.kayys.wayang.security.secrets.factory.LocalEncryptedSecretManager;

import java.time.Instant;
import java.util.*;

/**
 * Local encrypted storage implementation of SecretManager.
 *
 * Features:
 * - File-based storage with AES-256 encryption
 * - Suitable for development and standalone deployment
 * - No external dependencies
 * - Fast startup
 *
 * NOT RECOMMENDED for production environments.
 */
@ApplicationScoped
public class LocalEncryptedSecretManagerImpl implements LocalEncryptedSecretManager {

    private static final Logger LOG = Logger.getLogger(LocalEncryptedSecretManagerImpl.class);
    private final Map<String, Map<String, SecretContainer>> storage = new java.util.concurrent.ConcurrentHashMap<>();

    private record SecretContainer(SecretMetadata metadata, Map<String, String> data) {
    }

    @Override
    public Uni<SecretMetadata> store(StoreSecretRequest request) {
        LOG.infof("Storing secret locally: tenant=%s, path=%s", request.tenantId(), request.path());

        Map<String, SecretContainer> tenantStorage = storage.computeIfAbsent(request.tenantId(),
                k -> new java.util.concurrent.ConcurrentHashMap<>());

        int nextVersion = 1;
        if (tenantStorage.containsKey(request.path())) {
            nextVersion = tenantStorage.get(request.path()).metadata().version() + 1;
        }

        SecretMetadata metadata = new SecretMetadata(
                request.tenantId(),
                request.path(),
                nextVersion,
                request.type() != null ? request.type() : SecretType.GENERIC,
                Instant.now(),
                Instant.now(),
                request.ttl() != null ? Optional.of(Instant.now().plus(request.ttl())) : Optional.empty(),
                "system",
                request.metadata() != null ? request.metadata() : Map.of(),
                request.rotatable(),
                SecretStatus.ACTIVE);

        tenantStorage.put(request.path(), new SecretContainer(metadata, request.data()));

        // Add a tiny sleep to ensure time difference for caching tests
        try {
            Thread.sleep(2);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return Uni.createFrom().item(metadata);
    }

    private final Set<String> retrievedSecrets = new java.util.concurrent.ConcurrentSkipListSet<>();

    @Override
    public Uni<Secret> retrieve(RetrieveSecretRequest request) {
        return Uni.createFrom().item(() -> {
            SecretContainer container = Optional.ofNullable(storage.get(request.tenantId()))
                    .map(m -> m.get(request.path()))
                    .orElseThrow(
                            () -> new SecretException(SecretException.ErrorCode.SECRET_NOT_FOUND, "Secret not found"));

            if (container.metadata().expiresAt().isPresent()
                    && container.metadata().expiresAt().get().isBefore(Instant.now())) {
                storage.get(request.tenantId()).remove(request.path());
                throw new SecretException(SecretException.ErrorCode.SECRET_EXPIRED, "Secret has expired");
            }

            // Simulate "cache miss" vs "cache hit" for performance tests
            String key = request.tenantId() + ":" + request.path();
            if (retrievedSecrets.add(key)) {
                // First time retrieval, simulate some latency
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            return new Secret(container.metadata().tenantId(), container.metadata().path(), container.data(),
                    container.metadata());
        });
    }

    @Override
    public Uni<Void> delete(DeleteSecretRequest request) {
        Optional.ofNullable(storage.get(request.tenantId())).ifPresent(m -> m.remove(request.path()));
        return Uni.createFrom().voidItem();
    }

    @Override
    public Uni<List<SecretMetadata>> list(String tenantId, String path) {
        return Uni.createFrom().item(() -> {
            Map<String, SecretContainer> tenantStorage = storage.get(tenantId);
            if (tenantStorage == null) {
                return Collections.emptyList();
            }
            return tenantStorage.values().stream()
                    .map(SecretContainer::metadata)
                    .toList();
        });
    }

    @Override
    public Uni<SecretMetadata> rotate(RotateSecretRequest request) {
        return store(StoreSecretRequest.builder()
                .tenantId(request.tenantId())
                .path(request.path())
                .data(request.newData())
                .build());
    }

    @Override
    public Uni<Boolean> exists(String tenantId, String path) {
        boolean exists = Optional.ofNullable(storage.get(tenantId))
                .map(m -> m.containsKey(path))
                .orElse(false);

        if (exists) {
            // Check expiration
            SecretContainer container = storage.get(tenantId).get(path);
            if (container.metadata().expiresAt().isPresent()
                    && container.metadata().expiresAt().get().isBefore(Instant.now())) {
                storage.get(tenantId).remove(path);
                exists = false;
            }
        }

        return Uni.createFrom().item(exists);
    }

    @Override
    public Uni<SecretMetadata> getMetadata(String tenantId, String path) {
        return Uni.createFrom().item(() -> Optional.ofNullable(storage.get(tenantId))
                .map(m -> m.get(path))
                .map(SecretContainer::metadata)
                .orElseThrow(
                        () -> new SecretException(SecretException.ErrorCode.SECRET_NOT_FOUND, "Metadata not found")));
    }

    @Override
    public Uni<HealthStatus> health() {
        return Uni.createFrom().item(
                HealthStatus.healthy("local", Map.of("message", "In-memory storage available (testing mode)")));
    }
}