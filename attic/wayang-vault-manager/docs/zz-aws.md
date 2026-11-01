package tech.kayys.wayang.security.secrets.aws;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.*;
import tech.kayys.wayang.security.secrets.*;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * AWS Secrets Manager implementation of SecretManager.
 * 
 * Configuration:
 * - quarkus.secretsmanager.region=us-east-1
 * - quarkus.secretsmanager.endpoint-override=http://localhost:4566 (for LocalStack)
 * 
 * Features:
 * - Automatic rotation with Lambda
 * - KMS encryption
 * - Cross-region replication
 * - Resource-based policies
 * - Tagged secrets for organization
 */
@ApplicationScoped
public class AWSSecretsManager implements SecretManager {

    private static final Logger LOG = Logger.getLogger(AWSSecretsManager.class);
    private static final String TAG_TENANT = "tenant";
    private static final String TAG_TYPE = "type";
    private static final String TAG_ROTATABLE = "rotatable";

    @Inject
    SecretsManagerClient secretsManagerClient;

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "aws.secrets.prefix", defaultValue = "wayang/")
    String secretPrefix;

    @ConfigProperty(name = "aws.secrets.kms-key-id", defaultValue = "")
    Optional<String> kmsKeyId;

    @Inject
    VaultAuditLogger auditLogger;

    @Override
    public Uni<SecretMetadata> store(StoreSecretRequest request) {
        String secretName = buildSecretName(request.tenantId(), request.path());
        
        LOG.infof("Storing secret in AWS: %s for tenant: %s", secretName, request.tenantId());

        return Uni.createFrom().deferred(() -> {
            try {
                String secretString = objectMapper.writeValueAsString(request.data());
                
                // Check if secret exists
                return exists(request.tenantId(), request.path())
                    .onItem().transformToUni(secretExists -> {
                        if (secretExists) {
                            // Update existing secret
                            return updateSecret(secretName, secretString, request);
                        } else {
                            // Create new secret
                            return createSecret(secretName, secretString, request);
                        }
                    });

            } catch (Exception e) {
                LOG.errorf(e, "Failed to store secret: %s", secretName);
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

    private Uni<SecretMetadata> createSecret(String secretName, String secretString, 
                                            StoreSecretRequest request) {
        return Uni.createFrom().deferred(() -> {
            try {
                CreateSecretRequest.Builder builder = CreateSecretRequest.builder()
                    .name(secretName)
                    .secretString(secretString)
                    .description("Wayang secret: " + request.path())
                    .tags(buildTags(request));

                kmsKeyId.ifPresent(builder::kmsKeyId);

                CreateSecretResponse response = secretsManagerClient.createSecret(
                    builder.build()
                );

                SecretMetadata metadata = new SecretMetadata(
                    request.tenantId(),
                    request.path(),
                    1, // AWS doesn't expose version numbers directly
                    request.type(),
                    Instant.now(),
                    Instant.now(),
                    request.ttl() != null ? 
                        Optional.of(Instant.now().plus(request.ttl())) : Optional.empty(),
                    getCurrentUser(),
                    request.metadata(),
                    request.rotatable(),
                    SecretStatus.ACTIVE
                );

                auditLogger.logSecretStore(request.tenantId(), secretName, 1);

                return Uni.createFrom().item(metadata);

            } catch (Exception e) {
                return Uni.createFrom().failure(
                    new SecretException(
                        SecretException.ErrorCode.BACKEND_UNAVAILABLE,
                        "Failed to create secret: " + e.getMessage(),
                        e
                    )
                );
            }
        });
    }

    private Uni<SecretMetadata> updateSecret(String secretName, String secretString, 
                                            StoreSecretRequest request) {
        return Uni.createFrom().deferred(() -> {
            try {
                UpdateSecretRequest updateRequest = UpdateSecretRequest.builder()
                    .secretId(secretName)
                    .secretString(secretString)
                    .build();

                UpdateSecretResponse response = secretsManagerClient.updateSecret(updateRequest);

                SecretMetadata metadata = new SecretMetadata(
                    request.tenantId(),
                    request.path(),
                    1,
                    request.type(),
                    Instant.now(),
                    Instant.now(),
                    request.ttl() != null ? 
                        Optional.of(Instant.now().plus(request.ttl())) : Optional.empty(),
                    getCurrentUser(),
                    request.metadata(),
                    request.rotatable(),
                    SecretStatus.ACTIVE
                );

                auditLogger.logSecretStore(request.tenantId(), secretName, 1);

                return Uni.createFrom().item(metadata);

            } catch (Exception e) {
                return Uni.createFrom().failure(
                    new SecretException(
                        SecretException.ErrorCode.BACKEND_UNAVAILABLE,
                        "Failed to update secret: " + e.getMessage(),
                        e
                    )
                );
            }
        });
    }

    @Override
    public Uni<Secret> retrieve(RetrieveSecretRequest request) {
        String secretName = buildSecretName(request.tenantId(), request.path());
        
        LOG.debugf("Retrieving secret from AWS: %s", secretName);

        return Uni.createFrom().deferred(() -> {
            try {
                GetSecretValueRequest.Builder builder = GetSecretValueRequest.builder()
                    .secretId(secretName);

                request.version().ifPresent(v -> builder.versionId(String.valueOf(v)));

                GetSecretValueResponse response = secretsManagerClient.getSecretValue(
                    builder.build()
                );

                if (response.secretString() == null) {
                    return Uni.createFrom().failure(
                        new SecretException(
                            SecretException.ErrorCode.SECRET_NOT_FOUND,
                            "Secret not found: " + request.path()
                        )
                    );
                }

                @SuppressWarnings("unchecked")
                Map<String, String> secretData = objectMapper.readValue(
                    response.secretString(),
                    Map.class
                );

                SecretMetadata metadata = buildMetadataFromResponse(
                    request.tenantId(),
                    request.path(),
                    response
                );

                auditLogger.logSecretRetrieve(request.tenantId(), secretName, 
                    metadata.version());

                return Uni.createFrom().item(
                    new Secret(request.tenantId(), request.path(), secretData, metadata)
                );

            } catch (ResourceNotFoundException e) {
                return Uni.createFrom().failure(
                    new SecretException(
                        SecretException.ErrorCode.SECRET_NOT_FOUND,
                        "Secret not found: " + request.path()
                    )
                );
            } catch (Exception e) {
                LOG.errorf(e, "Failed to retrieve secret: %s", secretName);
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
        String secretName = buildSecretName(request.tenantId(), request.path());
        
        LOG.infof("Deleting secret from AWS: %s (hard=%b)", secretName, request.hardDelete());

        return Uni.createFrom().deferred(() -> {
            try {
                DeleteSecretRequest.Builder builder = DeleteSecretRequest.builder()
                    .secretId(secretName);

                if (request.hardDelete()) {
                    // Immediate deletion
                    builder.forceDeleteWithoutRecovery(true);
                } else {
                    // Schedule deletion (7 days recovery window by default)
                    builder.recoveryWindowInDays(7L);
                }

                secretsManagerClient.deleteSecret(builder.build());

                auditLogger.logSecretDelete(request.tenantId(), secretName, 
                    request.hardDelete(), request.reason());

                return Uni.createFrom().voidItem();

            } catch (Exception e) {
                LOG.errorf(e, "Failed to delete secret: %s", secretName);
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
        return Uni.createFrom().deferred(() -> {
            try {
                String filterPrefix = secretPrefix + tenantId + "/" + 
                    (path.isEmpty() ? "" : path + "/");

                ListSecretsRequest listRequest = ListSecretsRequest.builder()
                    .filters(Filter.builder()
                        .key(FilterNameStringType.NAME)
                        .values(filterPrefix)
                        .build())
                    .build();

                ListSecretsResponse response = secretsManagerClient.listSecrets(listRequest);

                List<SecretMetadata> metadataList = response.secretList().stream()
                    .map(secretListEntry -> {
                        String relativePath = secretListEntry.name()
                            .substring((secretPrefix + tenantId + "/").length());
                        
                        return new SecretMetadata(
                            tenantId,
                            relativePath,
                            1,
                            extractTypeFromTags(secretListEntry.tags()),
                            secretListEntry.createdDate(),
                            secretListEntry.lastChangedDate(),
                            Optional.empty(),
                            getCurrentUser(),
                            Map.of(),
                            extractRotatableFromTags(secretListEntry.tags()),
                            SecretStatus.ACTIVE
                        );
                    })
                    .collect(Collectors.toList());

                return Uni.createFrom().item(metadataList);

            } catch (Exception e) {
                LOG.errorf(e, "Failed to list secrets for tenant: %s", tenantId);
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
        String secretName = buildSecretName(request.tenantId(), request.path());
        
        LOG.infof("Rotating secret in AWS: %s", secretName);

        return Uni.createFrom().deferred(() -> {
            try {
                // AWS Secrets Manager has built-in rotation with Lambda
                // For manual rotation, we just update the secret
                String secretString = objectMapper.writeValueAsString(request.newData());

                UpdateSecretRequest updateRequest = UpdateSecretRequest.builder()
                    .secretId(secretName)
                    .secretString(secretString)
                    .build();

                UpdateSecretResponse response = secretsManagerClient.updateSecret(updateRequest);

                auditLogger.logSecretRotate(request.tenantId(), secretName, 0, 1);

                return getMetadata(request.tenantId(), request.path());

            } catch (Exception e) {
                return Uni.createFrom().failure(
                    new SecretException(
                        SecretException.ErrorCode.ROTATION_FAILED,
                        "Failed to rotate secret: " + e.getMessage(),
                        e
                    )
                );
            }
        });
    }

    @Override
    public Uni<Boolean> exists(String tenantId, String path) {
        String secretName = buildSecretName(tenantId, path);
        
        return Uni.createFrom().deferred(() -> {
            try {
                DescribeSecretRequest request = DescribeSecretRequest.builder()
                    .secretId(secretName)
                    .build();

                secretsManagerClient.describeSecret(request);
                return Uni.createFrom().item(true);

            } catch (ResourceNotFoundException e) {
                return Uni.createFrom().item(false);
            } catch (Exception e) {
                return Uni.createFrom().item(false);
            }
        });
    }

    @Override
    public Uni<SecretMetadata> getMetadata(String tenantId, String path) {
        String secretName = buildSecretName(tenantId, path);
        
        return Uni.createFrom().deferred(() -> {
            try {
                DescribeSecretRequest request = DescribeSecretRequest.builder()
                    .secretId(secretName)
                    .build();

                DescribeSecretResponse response = secretsManagerClient.describeSecret(request);

                SecretMetadata metadata = new SecretMetadata(
                    tenantId,
                    path,
                    1,
                    extractTypeFromTags(response.tags()),
                    response.createdDate(),
                    response.lastChangedDate(),
                    response.deletedDate() != null ? 
                        Optional.of(response.deletedDate()) : Optional.empty(),
                    getCurrentUser(),
                    Map.of(),
                    extractRotatableFromTags(response.tags()),
                    response.deletedDate() != null ? 
                        SecretStatus.DELETED : SecretStatus.ACTIVE
                );

                return Uni.createFrom().item(metadata);

            } catch (ResourceNotFoundException e) {
                return Uni.createFrom().failure(
                    new SecretException(
                        SecretException.ErrorCode.SECRET_NOT_FOUND,
                        "Secret not found: " + path
                    )
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
                // Try to list secrets to check connection
                ListSecretsRequest request = ListSecretsRequest.builder()
                    .maxResults(1)
                    .build();

                secretsManagerClient.listSecrets(request);

                Map<String, Object> details = Map.of(
                    "backend", "aws-secrets-manager",
                    "region", secretsManagerClient.serviceClientConfiguration().region().toString()
                );

                return Uni.createFrom().item(
                    new HealthStatus(true, "aws-secrets-manager", details, Optional.empty())
                );

            } catch (Exception e) {
                LOG.errorf(e, "AWS Secrets Manager health check failed");
                return Uni.createFrom().item(
                    HealthStatus.unhealthy("aws-secrets-manager", e.getMessage())
                );
            }
        });
    }

    // Helper methods

    private String buildSecretName(String tenantId, String path) {
        String normalizedPath = path.startsWith("/") ? path.substring(1) : path;
        return secretPrefix + tenantId + "/" + normalizedPath;
    }

    private List<Tag> buildTags(StoreSecretRequest request) {
        List<Tag> tags = new ArrayList<>();
        tags.add(Tag.builder().key(TAG_TENANT).value(request.tenantId()).build());
        tags.add(Tag.builder().key(TAG_TYPE).value(request.type().name()).build());
        tags.add(Tag.builder().key(TAG_ROTATABLE).value(String.valueOf(request.rotatable())).build());
        
        request.metadata().forEach((key, value) ->
            tags.add(Tag.builder().key(key).value(value).build())
        );
        
        return tags;
    }

    private SecretType extractTypeFromTags(List<Tag> tags) {
        return tags.stream()
            .filter(tag -> TAG_TYPE.equals(tag.key()))
            .findFirst()
            .map(tag -> SecretType.valueOf(tag.value()))
            .orElse(SecretType.GENERIC);
    }

    private boolean extractRotatableFromTags(List<Tag> tags) {
        return tags.stream()
            .filter(tag -> TAG_ROTATABLE.equals(tag.key()))
            .findFirst()
            .map(tag -> Boolean.parseBoolean(tag.value()))
            .orElse(false);
    }

    private SecretMetadata buildMetadataFromResponse(String tenantId, String path,
                                                     GetSecretValueResponse response) {
        return new SecretMetadata(
            tenantId,
            path,
            1,
            SecretType.GENERIC,
            response.createdDate(),
            Instant.now(),
            Optional.empty(),
            getCurrentUser(),
            Map.of(),
            false,
            SecretStatus.ACTIVE
        );
    }

    private String getCurrentUser() {
        return "system";
    }
}

