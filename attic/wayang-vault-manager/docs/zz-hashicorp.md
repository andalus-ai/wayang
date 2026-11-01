package tech.kayys.wayang.security.secrets.vault;

import io.quarkus.vault.VaultKVSecretEngine;
import io.quarkus.vault.client.VaultClient;
import io.quarkus.vault.client.api.VaultAuthApi;
import io.quarkus.vault.client.api.secrets.kv2.VaultSecretsKV2Api;
import io.quarkus.vault.client.http.VaultHttpClient;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import tech.kayys.wayang.security.secrets.*;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * HashiCorp Vault implementation of SecretManager.
 * 
 * Uses Vault KV v2 secrets engine with versioning support.
 * 
 * Configuration:
 * - quarkus.vault.url=http://localhost:8200
 * - quarkus.vault.authentication.client-token=<token>
 * - quarkus.vault.kv-secret-engine-version=2
 * - quarkus.vault.kv-secret-engine-mount-path=secret
 * - quarkus.vault.connect-timeout=5S
 * - quarkus.vault.read-timeout=1S
 * 
 * Features:
 * - KV v2 engine with automatic versioning
 * - AppRole authentication for applications
 * - Token renewal
 * - Audit logging
 * - Multi-tenancy via path prefixes
 */
@ApplicationScoped
public class VaultSecretManager implements SecretManager {

    private static final Logger LOG = Logger.getLogger(VaultSecretManager.class);
    private static final String TENANT_PATH_PREFIX = "tenants/";

    @Inject
    VaultKVSecretEngine kvEngine;

    @Inject
    VaultClient vaultClient;

    @ConfigProperty(name = "vault.secret.mount-path", defaultValue = "secret")
    String mountPath;

    @ConfigProperty(name = "vault.enable-audit", defaultValue = "true")
    boolean auditEnabled;

    @Inject
    VaultAuditLogger auditLogger;

    @Inject
    VaultTokenManager tokenManager;

    @Override
    public Uni<SecretMetadata> store(StoreSecretRequest request) {
        String fullPath = buildPath(request.tenantId(), request.path());
        
        LOG.infof("Storing secret at path: %s for tenant: %s", fullPath, request.tenantId());

        return Uni.createFrom().deferred(() -> {
            try {
                // Prepare secret data with metadata
                Map<String, Object> secretData = new HashMap<>();
                secretData.putAll(request.data());
                
                // Add internal metadata
                Map<String, Object> internalMetadata = new HashMap<>();
                internalMetadata.put("type", request.type().name());
                internalMetadata.put("created_at", Instant.now().toString());
                internalMetadata.put("tenant_id", request.tenantId());
                internalMetadata.put("rotatable", request.rotatable());
                if (request.ttl() != null) {
                    internalMetadata.put("ttl_seconds", request.ttl().getSeconds());
                    internalMetadata.put("expires_at", 
                        Instant.now().plus(request.ttl()).toString());
                }
                request.metadata().forEach(internalMetadata::put);

                // Write to Vault KV v2
                VaultSecretsKV2Api kv2Api = vaultClient.secrets().kv2(mountPath);
                
                var writeResponse = kv2Api.writeSecret(
                    fullPath,
                    secretData,
                    internalMetadata
                );

                int version = writeResponse.version();

                // Audit logging
                if (auditEnabled) {
                    auditLogger.logSecretStore(request.tenantId(), fullPath, version);
                }

                return Uni.createFrom().item(new SecretMetadata(
                    request.tenantId(),
                    request.path(),
                    version,
                    request.type(),
                    Instant.now(),
                    Instant.now(),
                    request.ttl() != null ? 
                        Optional.of(Instant.now().plus(request.ttl())) : Optional.empty(),
                    getCurrentUser(),
                    request.metadata(),
                    request.rotatable(),
                    SecretStatus.ACTIVE
                ));

            } catch (Exception e) {
                LOG.errorf(e, "Failed to store secret at path: %s", fullPath);
                return Uni.createFrom().failure(
                    new SecretException(
                        SecretException.ErrorCode.BACKEND_UNAVAILABLE,
                        "Failed to store secret: " + e.getMessage(),
                        e
                    )
                );
            }
        });
    }

    @Override
    public Uni<Secret> retrieve(RetrieveSecretRequest request) {
        String fullPath = buildPath(request.tenantId(), request.path());
        
        LOG.debugf("Retrieving secret from path: %s, version: %s", 
            fullPath, request.version().map(String::valueOf).orElse("latest"));

        return Uni.createFrom().deferred(() -> {
            try {
                VaultSecretsKV2Api kv2Api = vaultClient.secrets().kv2(mountPath);
                
                var readResponse = request.version().isPresent()
                    ? kv2Api.readSecretVersion(fullPath, request.version().get())
                    : kv2Api.readSecret(fullPath);

                if (readResponse == null || readResponse.data() == null) {
                    return Uni.createFrom().failure(
                        new SecretException(
                            SecretException.ErrorCode.SECRET_NOT_FOUND,
                            "Secret not found at path: " + request.path()
                        )
                    );
                }

                // Extract data
                Map<String, String> secretData = readResponse.data().entrySet().stream()
                    .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> String.valueOf(e.getValue())
                    ));

                // Extract metadata
                var metadata = readResponse.metadata();
                SecretMetadata secretMetadata = buildMetadata(
                    request.tenantId(),
                    request.path(),
                    metadata
                );

                // Check if expired
                if (secretMetadata.expiresAt().isPresent() && 
                    Instant.now().isAfter(secretMetadata.expiresAt().get())) {
                    return Uni.createFrom().failure(
                        new SecretException(
                            SecretException.ErrorCode.SECRET_NOT_FOUND,
                            "Secret has expired"
                        )
                    );
                }

                // Audit logging
                if (auditEnabled) {
                    auditLogger.logSecretRetrieve(request.tenantId(), fullPath, 
                        secretMetadata.version());
                }

                return Uni.createFrom().item(
                    new Secret(request.tenantId(), request.path(), secretData, secretMetadata)
                );

            } catch (SecretException e) {
                return Uni.createFrom().failure(e);
            } catch (Exception e) {
                LOG.errorf(e, "Failed to retrieve secret from path: %s", fullPath);
                return Uni.createFrom().failure(
                    new SecretException(
                        SecretException.ErrorCode.BACKEND_UNAVAILABLE,
                        "Failed to retrieve secret: " + e.getMessage(),
                        e
                    )
                );
            }
        });
    }

    @Override
    public Uni<Void> delete(DeleteSecretRequest request) {
        String fullPath = buildPath(request.tenantId(), request.path());
        
        LOG.infof("Deleting secret at path: %s (hard=%b)", fullPath, request.hardDelete());

        return Uni.createFrom().deferred(() -> {
            try {
                VaultSecretsKV2Api kv2Api = vaultClient.secrets().kv2(mountPath);
                
                if (request.hardDelete()) {
                    // Permanent deletion - destroys all versions
                    kv2Api.deleteMetadataAndAllVersions(fullPath);
                } else {
                    // Soft delete - marks latest version as deleted
                    kv2Api.deleteSecret(fullPath);
                }

                // Audit logging
                if (auditEnabled) {
                    auditLogger.logSecretDelete(request.tenantId(), fullPath, 
                        request.hardDelete(), request.reason());
                }

                return Uni.createFrom().voidItem();

            } catch (Exception e) {
                LOG.errorf(e, "Failed to delete secret at path: %s", fullPath);
                return Uni.createFrom().failure(
                    new SecretException(
                        SecretException.ErrorCode.BACKEND_UNAVAILABLE,
                        "Failed to delete secret: " + e.getMessage(),
                        e
                    )
                );
            }
        });
    }

    @Override
    public Uni<List<SecretMetadata>> list(String tenantId, String path) {
        String fullPath = buildPath(tenantId, path);
        
        return Uni.createFrom().deferred(() -> {
            try {
                VaultSecretsKV2Api kv2Api = vaultClient.secrets().kv2(mountPath);
                
                var listResponse = kv2Api.listSecrets(fullPath);
                
                if (listResponse == null || listResponse.keys() == null) {
                    return Uni.createFrom().item(List.of());
                }

                List<SecretMetadata> metadataList = new ArrayList<>();
                
                for (String key : listResponse.keys()) {
                    try {
                        String secretPath = path.isEmpty() ? key : path + "/" + key;
                        var metadata = kv2Api.readSecretMetadata(buildPath(tenantId, secretPath));
                        
                        if (metadata != null) {
                            metadataList.add(buildMetadata(tenantId, secretPath, 
                                metadata.currentVersion()));
                        }
                    } catch (Exception e) {
                        LOG.warnf("Failed to read metadata for key: %s", key);
                    }
                }

                return Uni.createFrom().item(metadataList);

            } catch (Exception e) {
                LOG.errorf(e, "Failed to list secrets at path: %s", fullPath);
                return Uni.createFrom().failure(
                    new SecretException(
                        SecretException.ErrorCode.BACKEND_UNAVAILABLE,
                        "Failed to list secrets: " + e.getMessage(),
                        e
                    )
                );
            }
        });
    }

    @Override
    public Uni<SecretMetadata> rotate(RotateSecretRequest request) {
        String fullPath = buildPath(request.tenantId(), request.path());
        
        LOG.infof("Rotating secret at path: %s", fullPath);

        return retrieve(RetrieveSecretRequest.of(request.tenantId(), request.path()))
            .onItem().transformToUni(currentSecret -> {
                // Create new version with updated data
                StoreSecretRequest storeRequest = StoreSecretRequest.builder()
                    .tenantId(request.tenantId())
                    .path(request.path())
                    .data(request.newData())
                    .type(currentSecret.metadata().type())
                    .metadata(currentSecret.metadata().metadata())
                    .rotatable(currentSecret.metadata().rotatable())
                    .build();

                return store(storeRequest)
                    .onItem().invoke(newMetadata -> {
                        if (auditEnabled) {
                            auditLogger.logSecretRotate(request.tenantId(), fullPath,
                                currentSecret.metadata().version(), newMetadata.version());
                        }
                    });
            });
    }

    @Override
    public Uni<Boolean> exists(String tenantId, String path) {
        return retrieve(RetrieveSecretRequest.of(tenantId, path))
            .onItem().transform(secret -> true)
            .onFailure(SecretException.class).recoverWithItem(false);
    }

    @Override
    public Uni<SecretMetadata> getMetadata(String tenantId, String path) {
        String fullPath = buildPath(tenantId, path);
        
        return Uni.createFrom().deferred(() -> {
            try {
                VaultSecretsKV2Api kv2Api = vaultClient.secrets().kv2(mountPath);
                var metadata = kv2Api.readSecretMetadata(fullPath);
                
                if (metadata == null) {
                    return Uni.createFrom().failure(
                        new SecretException(
                            SecretException.ErrorCode.SECRET_NOT_FOUND,
                            "Secret not found at path: " + path
                        )
                    );
                }

                return Uni.createFrom().item(
                    buildMetadata(tenantId, path, metadata.currentVersion())
                );

            } catch (Exception e) {
                return Uni.createFrom().failure(
                    new SecretException(
                        SecretException.ErrorCode.BACKEND_UNAVAILABLE,
                        "Failed to get metadata: " + e.getMessage(),
                        e
                    )
                );
            }
        });
    }

    @Override
    public Uni<HealthStatus> health() {
        return Uni.createFrom().deferred(() -> {
            try {
                // Try to authenticate to check connection
                vaultClient.auth().token().lookupSelf();
                
                Map<String, Object> details = Map.of(
                    "backend", "vault",
                    "mount_path", mountPath,
                    "authenticated", true
                );
                
                return Uni.createFrom().item(
                    new HealthStatus(true, "vault", details, Optional.empty())
                );

            } catch (Exception e) {
                LOG.errorf(e, "Vault health check failed");
                return Uni.createFrom().item(
                    HealthStatus.unhealthy("vault", e.getMessage())
                );
            }
        });
    }

    // Helper methods

    private String buildPath(String tenantId, String path) {
        // Normalize path
        String normalizedPath = path.startsWith("/") ? path.substring(1) : path;
        normalizedPath = normalizedPath.endsWith("/") ? 
            normalizedPath.substring(0, normalizedPath.length() - 1) : normalizedPath;
        
        return TENANT_PATH_PREFIX + tenantId + "/" + normalizedPath;
    }

    private SecretMetadata buildMetadata(String tenantId, String path, 
                                        Map<String, Object> vaultMetadata) {
        int version = vaultMetadata.containsKey("version") ? 
            ((Number) vaultMetadata.get("version")).intValue() : 1;
        
        String createdAtStr = (String) vaultMetadata.getOrDefault("created_at", 
            Instant.now().toString());
        Instant createdAt = Instant.parse(createdAtStr);
        
        String typeStr = (String) vaultMetadata.getOrDefault("type", "GENERIC");
        SecretType type = SecretType.valueOf(typeStr);
        
        Optional<Instant> expiresAt = Optional.empty();
        if (vaultMetadata.containsKey("expires_at")) {
            expiresAt = Optional.of(Instant.parse((String) vaultMetadata.get("expires_at")));
        }
        
        boolean rotatable = vaultMetadata.containsKey("rotatable") ? 
            (Boolean) vaultMetadata.get("rotatable") : false;

        return new SecretMetadata(
            tenantId,
            path,
            version,
            type,
            createdAt,
            createdAt,
            expiresAt,
            getCurrentUser(),
            Map.of(),
            rotatable,
            SecretStatus.ACTIVE
        );
    }

    private String getCurrentUser() {
        // Get from security context
        return "system";
    }
}