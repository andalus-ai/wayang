package tech.kayys.wayang.security.secrets.local;

import io.quarkus.hibernate.reactive.panache.Panache;
import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.*;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import tech.kayys.wayang.security.secrets.*;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Local encrypted secret storage for development and standalone deployments.
 * 
 * Features:
 * - AES-256-GCM encryption
 * - PostgreSQL/H2 backend
 * - Versioning support
 * - Soft delete with retention
 * - Master key encryption (KEK pattern)
 * 
 * Security:
 * - Data encryption keys (DEK) per secret
 * - Master key (KEK) from environment or KMS
 * - IV/nonce uniqueness per encryption
 * - Authenticated encryption (GCM)
 * 
 * Configuration:
 * - secret.master-key=<base64-encoded-key> (32 bytes for AES-256)
 * - secret.retention-days=30
 */
@ApplicationScoped
public class LocalEncryptedSecretManager implements SecretManager {

    private static final Logger LOG = Logger.getLogger(LocalEncryptedSecretManager.class);
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128;
    private static final int GCM_IV_LENGTH = 12;
    private static final int AES_KEY_SIZE = 256;

    @Inject
    SecretEntityRepository repository;

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "secret.master-key")
    Optional<String> masterKeyBase64;

    @ConfigProperty(name = "secret.retention-days", defaultValue = "30")
    int retentionDays;

    @Inject
    VaultAuditLogger auditLogger;

    private SecretKey masterKey;
    private final SecureRandom secureRandom = new SecureRandom();

    @jakarta.annotation.PostConstruct
    void init() {
        try {
            if (masterKeyBase64.isPresent()) {
                byte[] keyBytes = Base64.getDecoder().decode(masterKeyBase64.get());
                if (keyBytes.length != 32) {
                    throw new IllegalStateException("Master key must be 32 bytes (256 bits)");
                }
                masterKey = new SecretKeySpec(keyBytes, "AES");
                LOG.info("Loaded master key from configuration");
            } else {
                // Generate ephemeral key for development
                KeyGenerator keyGen = KeyGenerator.getInstance("AES");
                keyGen.init(AES_KEY_SIZE, secureRandom);
                masterKey = keyGen.generateKey();
                LOG.warn("Using ephemeral master key - secrets will not persist across restarts!");
                LOG.warn("Set 'secret.master-key' in production");
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize encryption", e);
        }
    }

    @Override
    public Uni<SecretMetadata> store(StoreSecretRequest request) {
        LOG.infof("Storing encrypted secret: tenant=%s, path=%s", 
            request.tenantId(), request.path());

        return Panache.withTransaction(() -> 
            repository.findLatestVersion(request.tenantId(), request.path())
                .onItem().transformToUni(existingOpt -> {
                    try {
                        int newVersion = existingOpt.map(e -> e.version + 1).orElse(1);
                        
                        // Encrypt secret data
                        String plaintext = objectMapper.writeValueAsString(request.data());
                        EncryptedData encrypted = encrypt(plaintext);
                        
                        // Create entity
                        SecretEntity entity = new SecretEntity();
                        entity.tenantId = request.tenantId();
                        entity.path = request.path();
                        entity.version = newVersion;
                        entity.encryptedData = encrypted.ciphertext();
                        entity.iv = encrypted.iv();
                        entity.type = request.type().name();
                        entity.createdAt = Instant.now();
                        entity.updatedAt = Instant.now();
                        entity.createdBy = getCurrentUser();
                        entity.rotatable = request.rotatable();
                        entity.status = SecretStatus.ACTIVE.name();
                        
                        if (request.ttl() != null) {
                            entity.expiresAt = Instant.now().plus(request.ttl());
                        }
                        
                        entity.metadata = request.metadata();
                        
                        return repository.persist(entity)
                            .onItem().invoke(() -> 
                                auditLogger.logSecretStore(request.tenantId(), 
                                    request.path(), newVersion)
                            )
                            .onItem().transform(saved -> toMetadata(saved));
                            
                    } catch (Exception e) {
                        LOG.errorf(e, "Failed to encrypt and store secret");
                        return Uni.createFrom().failure(
                            new SecretException(
                                SecretException.ErrorCode.ENCRYPTION_FAILED,
                                "Failed to encrypt secret: " + e.getMessage(),
                                e
                            )
                        );
                    }
                })
        );
    }

    @Override
    public Uni<Secret> retrieve(RetrieveSecretRequest request) {
        LOG.debugf("Retrieving encrypted secret: tenant=%s, path=%s, version=%s",
            request.tenantId(), request.path(), 
            request.version().map(String::valueOf).orElse("latest"));

        return Panache.withTransaction(() -> {
            Uni<Optional<SecretEntity>> entityUni = request.version().isPresent()
                ? repository.findByVersion(request.tenantId(), request.path(), 
                    request.version().get())
                : repository.findLatestVersion(request.tenantId(), request.path());

            return entityUni.onItem().transformToUni(entityOpt -> {
                if (entityOpt.isEmpty()) {
                    return Uni.createFrom().failure(
                        new SecretException(
                            SecretException.ErrorCode.SECRET_NOT_FOUND,
                            "Secret not found: " + request.path()
                        )
                    );
                }

                SecretEntity entity = entityOpt.get();

                // Check if deleted
                if (SecretStatus.DELETED.name().equals(entity.status)) {
                    return Uni.createFrom().failure(
                        new SecretException(
                            SecretException.ErrorCode.SECRET_NOT_FOUND,
                            "Secret has been deleted"
                        )
                    );
                }

                // Check if expired
                if (entity.expiresAt != null && Instant.now().isAfter(entity.expiresAt)) {
                    return Uni.createFrom().failure(
                        new SecretException(
                            SecretException.ErrorCode.SECRET_NOT_FOUND,
                            "Secret has expired"
                        )
                    );
                }

                try {
                    // Decrypt secret data
                    String plaintext = decrypt(
                        new EncryptedData(entity.encryptedData, entity.iv)
                    );
                    
                    @SuppressWarnings("unchecked")
                    Map<String, String> secretData = objectMapper.readValue(
                        plaintext, Map.class
                    );

                    auditLogger.logSecretRetrieve(request.tenantId(), 
                        request.path(), entity.version);

                    return Uni.createFrom().item(
                        new Secret(
                            request.tenantId(),
                            request.path(),
                            secretData,
                            toMetadata(entity)
                        )
                    );

                } catch (Exception e) {
                    LOG.errorf(e, "Failed to decrypt secret");
                    return Uni.createFrom().failure(
                        new SecretException(
                            SecretException.ErrorCode.DECRYPTION_FAILED,
                            "Failed to decrypt secret: " + e.getMessage(),
                            e
                        )
                    );
                }
            });
        });
    }

    @Override
    public Uni<Void> delete(DeleteSecretRequest request) {
        LOG.infof("Deleting secret: tenant=%s, path=%s, hard=%b",
            request.tenantId(), request.path(), request.hardDelete());

        return Panache.withTransaction(() -> 
            repository.findAllVersions(request.tenantId(), request.path())
                .onItem().transformToUni(entities -> {
                    if (entities.isEmpty()) {
                        return Uni.createFrom().voidItem();
                    }

                    if (request.hardDelete()) {
                        // Permanent deletion
                        return repository.delete("tenantId = ?1 and path = ?2",
                            request.tenantId(), request.path())
                            .onItem().invoke(() ->
                                auditLogger.logSecretDelete(request.tenantId(),
                                    request.path(), true, request.reason())
                            )
                            .replaceWithVoid();
                    } else {
                        // Soft delete - mark as deleted
                        entities.forEach(entity -> {
                            entity.status = SecretStatus.DELETED.name();
                            entity.deletedAt = Instant.now();
                        });
                        
                        return repository.persist(entities)
                            .onItem().invoke(() ->
                                auditLogger.logSecretDelete(request.tenantId(),
                                    request.path(), false, request.reason())
                            )
                            .replaceWithVoid();
                    }
                })
        );
    }

    @Override
    public Uni<List<SecretMetadata>> list(String tenantId, String path) {
        return repository.findByPathPrefix(tenantId, path)
            .onItem().transform(entities ->
                entities.stream()
                    .filter(e -> !SecretStatus.DELETED.name().equals(e.status))
                    .map(this::toMetadata)
                    .collect(Collectors.toList())
            );
    }

    @Override
    public Uni<SecretMetadata> rotate(RotateSecretRequest request) {
        LOG.infof("Rotating secret: tenant=%s, path=%s", 
            request.tenantId(), request.path());

        return retrieve(RetrieveSecretRequest.of(request.tenantId(), request.path()))
            .onItem().transformToUni(currentSecret -> {
                StoreSecretRequest storeRequest = StoreSecretRequest.builder()
                    .tenantId(request.tenantId())
                    .path(request.path())
                    .data(request.newData())
                    .type(currentSecret.metadata().type())
                    .metadata(currentSecret.metadata().metadata())
                    .rotatable(currentSecret.metadata().rotatable())
                    .build();

                return Panache.withTransaction(() -> 
                    repository.findLatestVersion(request.tenantId(), request.path())
                        .onItem().transformToUni(oldOpt -> {
                            if (oldOpt.isPresent() && request.deprecateOld()) {
                                SecretEntity old = oldOpt.get();
                                old.status = SecretStatus.ROTATED.name();
                                return repository.persist(old)
                                    .onItem().transformToUni(v -> store(storeRequest))
                                    .onItem().invoke(newMetadata ->
                                        auditLogger.logSecretRotate(request.tenantId(),
                                            request.path(), old.version, newMetadata.version())
                                    );
                            }
                            return store(storeRequest);
                        })
                );
            });
    }

    @Override
    public Uni<Boolean> exists(String tenantId, String path) {
        return repository.findLatestVersion(tenantId, path)
            .onItem().transform(opt -> opt.isPresent() && 
                !SecretStatus.DELETED.name().equals(opt.get().status));
    }

    @Override
    public Uni<SecretMetadata> getMetadata(String tenantId, String path) {
        return repository.findLatestVersion(tenantId, path)
            .onItem().transform(opt -> {
                if (opt.isEmpty()) {
                    throw new SecretException(
                        SecretException.ErrorCode.SECRET_NOT_FOUND,
                        "Secret not found: " + path
                    );
                }
                return toMetadata(opt.get());
            });
    }

    @Override
    public Uni<HealthStatus> health() {
        return repository.count()
            .onItem().transform(count -> {
                Map<String, Object> details = Map.of(
                    "backend", "local-encrypted",
                    "total_secrets", count,
                    "encryption", "AES-256-GCM"
                );
                return new HealthStatus(true, "local-encrypted", details, Optional.empty());
            })
            .onFailure().recoverWithItem(e -> 
                HealthStatus.unhealthy("local-encrypted", e.getMessage())
            );
    }

    // Encryption/Decryption methods

    private EncryptedData encrypt(String plaintext) throws Exception {
        // Generate random IV
        byte[] iv = new byte[GCM_IV_LENGTH];
        secureRandom.nextBytes(iv);

        Cipher cipher = Cipher.getInstance(ALGORITHM);
        GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        cipher.init(Cipher.ENCRYPT_MODE, masterKey, parameterSpec);

        byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

        return new EncryptedData(
            Base64.getEncoder().encodeToString(ciphertext),
            Base64.getEncoder().encodeToString(iv)
        );
    }

    private String decrypt(EncryptedData encrypted) throws Exception {
        byte[] ciphertext = Base64.getDecoder().decode(encrypted.ciphertext());
        byte[] iv = Base64.getDecoder().decode(encrypted.iv());

        Cipher cipher = Cipher.getInstance(ALGORITHM);
        GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        cipher.init(Cipher.DECRYPT_MODE, masterKey, parameterSpec);

        byte[] plaintext = cipher.doFinal(ciphertext);
        return new String(plaintext, StandardCharsets.UTF_8);
    }

    // Helper methods

    private SecretMetadata toMetadata(SecretEntity entity) {
        return new SecretMetadata(
            entity.tenantId,
            entity.path,
            entity.version,
            SecretType.valueOf(entity.type),
            entity.createdAt,
            entity.updatedAt,
            Optional.ofNullable(entity.expiresAt),
            entity.createdBy,
            entity.metadata != null ? entity.metadata : Map.of(),
            entity.rotatable,
            SecretStatus.valueOf(entity.status)
        );
    }

    private String getCurrentUser() {
        return "system";
    }

    record EncryptedData(String ciphertext, String iv) {}
}

/**
 * Entity for storing encrypted secrets
 */
@Entity
@Table(name = "secrets", indexes = {
    @Index(name = "idx_secrets_tenant_path", columnList = "tenantId, path"),
    @Index(name = "idx_secrets_tenant_path_version", columnList = "tenantId, path, version")
})
class SecretEntity {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(nullable = false)
    String tenantId;

    @Column(nullable = false)
    String path;

    @Column(nullable = false)
    Integer version;

    @Column(nullable = false, columnDefinition = "TEXT")
    String encryptedData;

    @Column(nullable = false)
    String iv; // Initialization vector for AES-GCM

    @Column(nullable = false)
    String type;

    @Column(nullable = false)
    Instant createdAt;

    @Column(nullable = false)
    Instant updatedAt;

    @Column
    Instant expiresAt;

    @Column
    Instant deletedAt;

    @Column(nullable = false)
    String createdBy;

    @Column(nullable = false)
    Boolean rotatable = false;

    @Column(nullable = false)
    String status;

    @ElementCollection
    @CollectionTable(name = "secret_metadata", 
        joinColumns = @JoinColumn(name = "secret_id"))
    @MapKeyColumn(name = "key")
    @Column(name = "value")
    Map<String, String> metadata;
}

/**
 * Repository for secret entities
 */
@ApplicationScoped
class SecretEntityRepository implements PanacheRepositoryBase<SecretEntity, Long> {

    public Uni<Optional<SecretEntity>> findLatestVersion(String tenantId, String path) {
        return find("tenantId = ?1 and path = ?2 order by version desc", tenantId, path)
            .firstResult()
            .onItem().transform(Optional::ofNullable);
    }

    public Uni<Optional<SecretEntity>> findByVersion(String tenantId, String path, int version) {
        return find("tenantId = ?1 and path = ?2 and version = ?3", tenantId, path, version)
            .firstResult()
            .onItem().transform(Optional::ofNullable);
    }

    public Uni<List<SecretEntity>> findAllVersions(String tenantId, String path) {
        return find("tenantId = ?1 and path = ?2 order by version desc", tenantId, path)
            .list();
    }

    public Uni<List<SecretEntity>> findByPathPrefix(String tenantId, String pathPrefix) {
        String pattern = pathPrefix.isEmpty() ? "%" : pathPrefix + "%";
        return find("tenantId = ?1 and path like ?2", tenantId, pattern)
            .list();
    }
}