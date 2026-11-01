package tech.kayys.wayang.security.apikey.api;

import io.quarkus.security.Authenticated;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.jboss.logging.Logger;
import tech.kayys.wayang.security.apikey.*;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * REST API for API Key management
 */
@Path("/api/v1/apikeys")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
public class APIKeyResource {
    
    private static final Logger LOG = Logger.getLogger(APIKeyResource.class);
    
    @Inject
    APIKeyService apiKeyService;
    
    @Context
    SecurityContext securityContext;
    
    /**
     * Create a new API key
     */
    @POST
    public Uni<Response> createAPIKey(CreateAPIKeyApiRequest request) {
        String tenantId = getTenantId();
        String createdBy = getUserId();
        
        LOG.infof("Creating API key: tenant=%s, name=%s", tenantId, request.name);
        
        CreateAPIKeyRequest serviceRequest = new CreateAPIKeyRequest(
            tenantId,
            request.name,
            request.scopes,
            request.environment != null ? request.environment : "live",
            request.expiresInDays != null ? Duration.ofDays(request.expiresInDays) : null,
            createdBy,
            request.metadata != null ? request.metadata : Map.of()
        );
        
        return apiKeyService.createAPIKey(serviceRequest)
            .onItem().transform(result -> Response
                .status(Response.Status.CREATED)
                .entity(result)
                .build())
            .onFailure().recoverWithItem(th -> {
                LOG.errorf(th, "Failed to create API key");
                return Response
                    .status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", th.getMessage()))
                    .build();
            });
    }
    
    /**
     * List all API keys for current tenant
     */
    @GET
    public Uni<Response> listAPIKeys() {
        String tenantId = getTenantId();
        
        return apiKeyService.listAPIKeys(tenantId)
            .onItem().transform(keys -> Response
                .ok(Map.of(
                    "keys", keys,
                    "count", keys.size()
                ))
                .build());
    }
    
    /**
     * Get API key details (without revealing the actual key)
     */
    @GET
    @Path("/{keyId}")
    public Uni<Response> getAPIKey(@PathParam("keyId") String keyId) {
        String tenantId = getTenantId();
        
        return apiKeyService.listAPIKeys(tenantId)
            .onItem().transform(keys -> {
                return keys.stream()
                    .filter(k -> k.id().equals(keyId))
                    .findFirst()
                    .map(key -> Response.ok(key).build())
                    .orElse(Response.status(Response.Status.NOT_FOUND).build());
            });
    }
    
    /**
     * Revoke an API key
     */
    @DELETE
    @Path("/{keyId}")
    public Uni<Response> revokeAPIKey(
            @PathParam("keyId") String keyId,
            RevokeAPIKeyRequest request) {
        
        LOG.infof("Revoking API key: %s", keyId);
        
        String reason = request != null && request.reason != null ? 
            request.reason : "Revoked via API";
        
        return apiKeyService.revokeAPIKey(keyId, reason)
            .onItem().transform(v -> Response.noContent().build())
            .onFailure(APIKeyNotFoundException.class).recoverWithItem(
                Response.status(Response.Status.NOT_FOUND).build()
            )
            .onFailure().recoverWithItem(th -> {
                LOG.errorf(th, "Failed to revoke API key");
                return Response
                    .status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", th.getMessage()))
                    .build();
            });
    }
    
    /**
     * Rotate an API key (create new, revoke old)
     */
    @POST
    @Path("/{keyId}/rotate")
    public Uni<Response> rotateAPIKey(@PathParam("keyId") String keyId) {
        LOG.infof("Rotating API key: %s", keyId);
        
        return apiKeyService.rotateAPIKey(keyId)
            .onItem().transform(result -> Response.ok(result).build())
            .onFailure(APIKeyNotFoundException.class).recoverWithItem(
                Response.status(Response.Status.NOT_FOUND).build()
            )
            .onFailure().recoverWithItem(th -> {
                LOG.errorf(th, "Failed to rotate API key");
                return Response
                    .status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", th.getMessage()))
                    .build();
            });
    }
    
    /**
     * Update API key scopes
     */
    @PATCH
    @Path("/{keyId}/scopes")
    public Uni<Response> updateScopes(
            @PathParam("keyId") String keyId,
            UpdateScopesRequest request) {
        
        LOG.infof("Updating scopes for API key: %s", keyId);
        
        return apiKeyService.updateScopes(keyId, request.scopes)
            .onItem().transform(v -> Response.noContent().build())
            .onFailure(APIKeyNotFoundException.class).recoverWithItem(
                Response.status(Response.Status.NOT_FOUND).build()
            )
            .onFailure().recoverWithItem(th -> {
                LOG.errorf(th, "Failed to update scopes");
                return Response
                    .status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", th.getMessage()))
                    .build();
            });
    }
    
    /**
     * Get API key usage statistics
     */
    @GET
    @Path("/{keyId}/usage")
    public Uni<Response> getUsageStats(
            @PathParam("keyId") String keyId,
            @QueryParam("days") @DefaultValue("30") int days) {
        
        return apiKeyService.getUsageStats(keyId, Duration.ofDays(days))
            .onItem().transform(stats -> Response.ok(stats).build())
            .onFailure(APIKeyNotFoundException.class).recoverWithItem(
                Response.status(Response.Status.NOT_FOUND).build()
            );
    }
    
    private String getTenantId() {
        // Extract from JWT or security context
        return "default-tenant";
    }
    
    private String getUserId() {
        return securityContext.getUserPrincipal().getName();
    }
}

// Request DTOs

record CreateAPIKeyApiRequest(
    String name,
    List<String> scopes,
    String environment,
    Integer expiresInDays,
    Map<String, String> metadata
) {}

record RevokeAPIKeyRequest(String reason) {}

record UpdateScopesRequest(Set<String> scopes) {}

/**
 * Authentication filter for API key validation
 */
@Provider
@Priority(Priorities.AUTHENTICATION)
public class APIKeyAuthenticationFilter implements ContainerRequestFilter {
    
    private static final Logger LOG = Logger.getLogger(APIKeyAuthenticationFilter.class);
    private static final String API_KEY_HEADER = "X-API-Key";
    private static final String AUTHORIZATION_HEADER = "Authorization";
    
    @Inject
    APIKeyService apiKeyService;
    
    @Override
    public void filter(ContainerRequestContext requestContext) {
        // Extract API key from header
        String apiKey = extractAPIKey(requestContext);
        
        if (apiKey == null) {
            // No API key provided, let other auth mechanisms handle it
            return;
        }
        
        // Validate API key
        APIKeyValidationResult result = apiKeyService.validateAPIKey(apiKey)
            .await().indefinitely();
        
        if (!result.valid()) {
            LOG.warnf("Invalid API key: %s", result.error());
            requestContext.abortWith(
                Response.status(Response.Status.UNAUTHORIZED)
                    .entity(Map.of("error", "Invalid API key: " + result.error()))
                    .build()
            );
            return;
        }
        
        // Set security context
        requestContext.setSecurityContext(new APIKeySecurityContext(
            result.tenantId(),
            result.keyId(),
            result.scopes(),
            requestContext.getSecurityContext().isSecure()
        ));
        
        // Add tenant ID to request context
        requestContext.setProperty("tenantId", result.tenantId());
        requestContext.setProperty("apiKeyId", result.keyId());
    }
    
    private String extractAPIKey(ContainerRequestContext requestContext) {
        // Try X-API-Key header first
        String apiKey = requestContext.getHeaderString(API_KEY_HEADER);
        if (apiKey != null && !apiKey.isBlank()) {
            return apiKey;
        }
        
        // Try Authorization: Bearer header
        String authHeader = requestContext.getHeaderString(AUTHORIZATION_HEADER);
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            if (token.startsWith("wayang_")) {
                return token;
            }
        }
        
        return null;
    }
}

/**
 * Security context for API key authentication
 */
class APIKeySecurityContext implements SecurityContext {
    
    private final String tenantId;
    private final String keyId;
    private final Set<String> scopes;
    private final boolean secure;
    
    public APIKeySecurityContext(String tenantId, String keyId, 
                                Set<String> scopes, boolean secure) {
        this.tenantId = tenantId;
        this.keyId = keyId;
        this.scopes = scopes;
        this.secure = secure;
    }
    
    @Override
    public Principal getUserPrincipal() {
        return () -> "api-key:" + keyId;
    }
    
    @Override
    public boolean isUserInRole(String role) {
        return scopes.contains(role);
    }
    
    @Override
    public boolean isSecure() {
        return secure;
    }
    
    @Override
    public String getAuthenticationScheme() {
        return "API-Key";
    }
    
    public String getTenantId() {
        return tenantId;
    }
    
    public Set<String> getScopes() {
        return scopes;
    }
}

/**
 * Scope-based authorization interceptor
 */
@Provider
@Priority(Priorities.AUTHORIZATION)
public class ScopeAuthorizationFilter implements ContainerRequestFilter {
    
    private static final Logger LOG = Logger.getLogger(ScopeAuthorizationFilter.class);
    
    @Override
    public void filter(ContainerRequestContext requestContext) {
        SecurityContext securityContext = requestContext.getSecurityContext();
        
        if (!(securityContext instanceof APIKeySecurityContext apiKeyContext)) {
            return; // Not API key auth
        }
        
        // Extract required scope from annotation (if available)
        // This is simplified - in production, use @RolesAllowed or custom annotation
        String path = requestContext.getUriInfo().getPath();
        String method = requestContext.getMethod();
        
        String requiredScope = determineRequiredScope(path, method);
        
        if (requiredScope != null && !apiKeyContext.getScopes().contains(requiredScope)) {
            LOG.warnf("Insufficient scope: required=%s, available=%s", 
                requiredScope, apiKeyContext.getScopes());
            
            requestContext.abortWith(
                Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of(
                        "error", "Insufficient scope",
                        "required", requiredScope,
                        "available", apiKeyContext.getScopes()
                    ))
                    .build()
            );
        }
    }
    
    private String determineRequiredScope(String path, String method) {
        // Simple scope mapping
        if (path.startsWith("/api/v1/workflows")) {
            return switch (method) {
                case "GET" -> "workflows:read";
                case "POST", "PUT", "PATCH" -> "workflows:write";
                case "DELETE" -> "workflows:delete";
                default -> null;
            };
        }
        
        if (path.startsWith("/api/v1/secrets")) {
            return switch (method) {
                case "GET" -> "secrets:read";
                case "POST", "PUT", "PATCH" -> "secrets:write";
                case "DELETE" -> "secrets:delete";
                default -> null;
            };
        }
        
        return null; // No scope required
    }
}

/**
 * Standard scopes for the platform
 */
public interface Scopes {
    // Workflow scopes
    String WORKFLOWS_READ = "workflows:read";
    String WORKFLOWS_WRITE = "workflows:write";
    String WORKFLOWS_DELETE = "workflows:delete";
    String WORKFLOWS_EXECUTE = "workflows:execute";
    
    // Secret scopes
    String SECRETS_READ = "secrets:read";
    String SECRETS_WRITE = "secrets:write";
    String SECRETS_DELETE = "secrets:delete";
    
    // Node scopes
    String NODES_READ = "nodes:read";
    String NODES_WRITE = "nodes:write";
    
    // Agent scopes
    String AGENTS_READ = "agents:read";
    String AGENTS_WRITE = "agents:write";
    String AGENTS_EXECUTE = "agents:execute";
    
    // Admin scopes
    String ADMIN_FULL = "admin:full";
    String ADMIN_USERS = "admin:users";
    String ADMIN_APIKEYS = "admin:apikeys";
    
    // Wildcard
    String ALL = "*";
}


package tech.kayys.wayang.security.secrets.api;

import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import tech.kayys.wayang.security.secrets.*;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * REST API for secret management.
 * 
 * Endpoints:
 * - POST /api/v1/secrets - Store a secret
 * - GET /api/v1/secrets/{path} - Retrieve a secret
 * - DELETE /api/v1/secrets/{path} - Delete a secret
 * - GET /api/v1/secrets - List secrets
 * - POST /api/v1/secrets/{path}/rotate - Rotate a secret
 * - GET /api/v1/secrets/{path}/metadata - Get metadata only
 * - GET /api/v1/secrets/health - Health check
 * 
 * Security:
 * - All endpoints require authentication
 * - Tenant isolation enforced
 * - Audit logging for all operations
 */
@Path("/api/v1/secrets")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SecretResource {

    private static final Logger LOG = Logger.getLogger(SecretResource.class);

    @Inject
    SecretManager secretManager;

    /**
     * Store a new secret or update existing one
     */
    @POST
    public Uni<Response> storeSecret(StoreSecretApiRequest request) {
        LOG.infof("Storing secret: tenant=%s, path=%s", request.tenantId, request.path);

        StoreSecretRequest storeRequest = StoreSecretRequest.builder()
            .tenantId(request.tenantId)
            .path(request.path)
            .data(request.data)
            .type(request.type != null ? SecretType.valueOf(request.type) : SecretType.GENERIC)
            .ttl(request.ttlSeconds != null ? Duration.ofSeconds(request.ttlSeconds) : null)
            .metadata(request.metadata != null ? request.metadata : Map.of())
            .rotatable(request.rotatable != null ? request.rotatable : false)
            .build();

        return secretManager.store(storeRequest)
            .onItem().transform(metadata -> Response
                .status(Response.Status.CREATED)
                .entity(SecretMetadataResponse.from(metadata))
                .build())
            .onFailure().recoverWithItem(th -> {
                LOG.errorf(th, "Failed to store secret: %s", request.path);
                return Response
                    .status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ErrorResponse.from(th))
                    .build();
            });
    }

    /**
     * Retrieve a secret by path
     */
    @GET
    @Path("/{path:.+}")
    public Uni<Response> getSecret(
            @PathParam("path") String path,
            @QueryParam("tenantId") String tenantId,
            @QueryParam("version") Integer version) {
        
        LOG.debugf("Retrieving secret: tenant=%s, path=%s, version=%s", 
            tenantId, path, version);

        RetrieveSecretRequest request = new RetrieveSecretRequest(
            tenantId,
            path,
            Optional.ofNullable(version)
        );

        return secretManager.retrieve(request)
            .onItem().transform(secret -> Response
                .ok(SecretResponse.from(secret))
                .build())
            .onFailure(SecretException.class).recoverWithItem(ex -> {
                SecretException se = (SecretException) ex;
                if (se.getErrorCode() == SecretException.ErrorCode.SECRET_NOT_FOUND) {
                    return Response
                        .status(Response.Status.NOT_FOUND)
                        .entity(ErrorResponse.from(ex))
                        .build();
                }
                return Response
                    .status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ErrorResponse.from(ex))
                    .build();
            })
            .onFailure().recoverWithItem(th -> Response
                .status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(ErrorResponse.from(th))
                .build());
    }

    /**
     * Delete a secret
     */
    @DELETE
    @Path("/{path:.+}")
    public Uni<Response> deleteSecret(
            @PathParam("path") String path,
            @QueryParam("tenantId") String tenantId,
            @QueryParam("hard") @DefaultValue("false") boolean hard,
            @QueryParam("reason") String reason) {
        
        LOG.infof("Deleting secret: tenant=%s, path=%s, hard=%b", tenantId, path, hard);

        DeleteSecretRequest request = new DeleteSecretRequest(
            tenantId,
            path,
            hard,
            reason != null ? reason : "Deleted via API"
        );

        return secretManager.delete(request)
            .onItem().transform(v -> Response.noContent().build())
            .onFailure().recoverWithItem(th -> Response
                .status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(ErrorResponse.from(th))
                .build());
    }

    /**
     * List secrets by path prefix
     */
    @GET
    public Uni<Response> listSecrets(
            @QueryParam("tenantId") String tenantId,
            @QueryParam("path") @DefaultValue("") String path) {
        
        LOG.debugf("Listing secrets: tenant=%s, path=%s", tenantId, path);

        return secretManager.list(tenantId, path)
            .onItem().transform(metadataList -> Response
                .ok(SecretListResponse.from(metadataList))
                .build())
            .onFailure().recoverWithItem(th -> Response
                .status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(ErrorResponse.from(th))
                .build());
    }

    /**
     * Rotate a secret (create new version)
     */
    @POST
    @Path("/{path:.+}/rotate")
    public Uni<Response> rotateSecret(
            @PathParam("path") String path,
            RotateSecretApiRequest request) {
        
        LOG.infof("Rotating secret: tenant=%s, path=%s", request.tenantId, path);

        RotateSecretRequest rotateRequest = new RotateSecretRequest(
            request.tenantId,
            path,
            request.newData,
            request.deprecateOld != null ? request.deprecateOld : true
        );

        return secretManager.rotate(rotateRequest)
            .onItem().transform(metadata -> Response
                .ok(SecretMetadataResponse.from(metadata))
                .build())
            .onFailure().recoverWithItem(th -> Response
                .status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(ErrorResponse.from(th))
                .build());
    }

    /**
     * Get secret metadata without retrieving the value
     */
    @GET
    @Path("/{path:.+}/metadata")
    public Uni<Response> getMetadata(
            @PathParam("path") String path,
            @QueryParam("tenantId") String tenantId) {
        
        return secretManager.getMetadata(tenantId, path)
            .onItem().transform(metadata -> Response
                .ok(SecretMetadataResponse.from(metadata))
                .build())
            .onFailure(SecretException.class).recoverWithItem(ex -> {
                SecretException se = (SecretException) ex;
                if (se.getErrorCode() == SecretException.ErrorCode.SECRET_NOT_FOUND) {
                    return Response.status(Response.Status.NOT_FOUND).build();
                }
                return Response
                    .status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ErrorResponse.from(ex))
                    .build();
            });
    }

    /**
     * Check if secret exists
     */
    @HEAD
    @Path("/{path:.+}")
    public Uni<Response> secretExists(
            @PathParam("path") String path,
            @QueryParam("tenantId") String tenantId) {
        
        return secretManager.exists(tenantId, path)
            .onItem().transform(exists -> exists
                ? Response.ok().build()
                : Response.status(Response.Status.NOT_FOUND).build());
    }

    /**
     * Health check endpoint
     */
    @GET
    @Path("/health")
    public Uni<Response> health() {
        return secretManager.health()
            .onItem().transform(health -> Response
                .status(health.healthy() ? Response.Status.OK : Response.Status.SERVICE_UNAVAILABLE)
                .entity(health)
                .build());
    }
}

// API Request/Response DTOs

record StoreSecretApiRequest(
    String tenantId,
    String path,
    Map<String, String> data,
    String type,
    Long ttlSeconds,
    Map<String, String> metadata,
    Boolean rotatable
) {}

record RotateSecretApiRequest(
    String tenantId,
    Map<String, String> newData,
    Boolean deprecateOld
) {}

record SecretResponse(
    String tenantId,
    String path,
    Map<String, String> data,
    SecretMetadataResponse metadata
) {
    static SecretResponse from(Secret secret) {
        return new SecretResponse(
            secret.tenantId(),
            secret.path(),
            secret.data(),
            SecretMetadataResponse.from(secret.metadata())
        );
    }
}

record SecretMetadataResponse(
    String tenantId,
    String path,
    int version,
    String type,
    String createdAt,
    String updatedAt,
    String expiresAt,
    String createdBy,
    Map<String, String> metadata,
    boolean rotatable,
    String status
) {
    static SecretMetadataResponse from(SecretMetadata metadata) {
        return new SecretMetadataResponse(
            metadata.tenantId(),
            metadata.path(),
            metadata.version(),
            metadata.type().name(),
            metadata.createdAt().toString(),
            metadata.updatedAt().toString(),
            metadata.expiresAt().map(Object::toString).orElse(null),
            metadata.createdBy(),
            metadata.metadata(),
            metadata.rotatable(),
            metadata.status().name()
        );
    }
}

record SecretListResponse(
    int count,
    List<SecretMetadataResponse> secrets
) {
    static SecretListResponse from(List<SecretMetadata> metadataList) {
        return new SecretListResponse(
            metadataList.size(),
            metadataList.stream()
                .map(SecretMetadataResponse::from)
                .toList()
        );
    }
}

record ErrorResponse(
    String error,
    String message,
    String code
) {
    static ErrorResponse from(Throwable th) {
        if (th instanceof SecretException se) {
            return new ErrorResponse(
                "SecretException",
                se.getMessage(),
                se.getErrorCode().name()
            );
        }
        return new ErrorResponse(
            th.getClass().getSimpleName(),
            th.getMessage(),
            "UNKNOWN"
        );
    }
}