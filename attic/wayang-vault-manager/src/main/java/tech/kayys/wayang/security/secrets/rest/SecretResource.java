package tech.kayys.wayang.security.secrets.rest;

import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import tech.kayys.wayang.error.ErrorCode;
import tech.kayys.wayang.error.WayangException;
import tech.kayys.wayang.security.secrets.core.DefaultSecretManager;
import tech.kayys.wayang.security.secrets.core.SecretManager;
import tech.kayys.wayang.security.secrets.dto.*;
import tech.kayys.wayang.security.secrets.exception.SecretException;

import java.util.*;

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
 */
@Path("/api/v1/secrets")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SecretResource {

    private static final Logger LOG = Logger.getLogger(SecretResource.class);

    @Inject
    @DefaultSecretManager
    SecretManager secretManager;

    /**
     * Store a new secret or update existing one
     */
    @POST
    public Uni<Response> storeSecret(StoreSecretApiRequest request) {
        LOG.infof("Storing secret: tenant=%s, path=%s", request.tenantId(), request.path());

        StoreSecretRequest storeRequest = StoreSecretRequest.builder()
                .tenantId(request.tenantId())
                .path(request.path())
                .data(request.data())
                .type(request.type() != null ? SecretType.valueOf(request.type()) : SecretType.GENERIC)
                .metadata(request.metadata() != null ? request.metadata() : Map.of())
                .rotatable(request.rotatable() != null ? request.rotatable() : false)
                .build();

        return secretManager.store(storeRequest)
                .onItem().transform(metadata -> Response
                        .status(Response.Status.CREATED)
                        .entity(SecretMetadataResponse.from(metadata))
                        .build())
                .onFailure().recoverWithItem(th -> {
                    LOG.errorf(th, "Failed to store secret: %s", request.path());
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
                Optional.ofNullable(version));

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
                reason != null ? reason : "Deleted via API");

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

        LOG.infof("Rotating secret: tenant=%s, path=%s", request.tenantId(), path);

        RotateSecretRequest rotateRequest = new RotateSecretRequest(
                request.tenantId(),
                path,
                request.newData(),
                request.deprecateOld() != null ? request.deprecateOld() : true);

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
        Boolean rotatable) {
}

record RotateSecretApiRequest(
        String tenantId,
        Map<String, String> newData,
        Boolean deprecateOld) {
}

record SecretResponse(
        String tenantId,
        String path,
        Map<String, String> data,
        SecretMetadataResponse metadata) {
    static SecretResponse from(Secret secret) {
        return new SecretResponse(
                secret.tenantId(),
                secret.path(),
                secret.data(),
                SecretMetadataResponse.from(secret.metadata()));
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
        String status) {
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
                metadata.status().name());
    }
}

record SecretListResponse(
        int count,
        List<SecretMetadataResponse> secrets) {
    static SecretListResponse from(List<SecretMetadata> metadataList) {
        return new SecretListResponse(
                metadataList.size(),
                metadataList.stream()
                        .map(SecretMetadataResponse::from)
                        .toList());
    }
}

record ErrorResponse(
        String error,
        String message,
        String code) {
    static ErrorResponse from(Throwable th) {
        if (th instanceof WayangException we) {
            ErrorCode errorCode = we.getErrorCode();
            return new ErrorResponse(
                    we.getClass().getSimpleName(),
                    we.getMessage(),
                    errorCode.getCode());
        }
        if (th instanceof SecretException se) {
            ErrorCode errorCode = mapSecretErrorCode(se.getErrorCode());
            return new ErrorResponse(
                    "SecretException",
                    se.getMessage(),
                    errorCode.getCode());
        }
        return new ErrorResponse(
                th.getClass().getSimpleName(),
                th.getMessage(),
                ErrorCode.INTERNAL_ERROR.getCode());
    }

    private static ErrorCode mapSecretErrorCode(SecretException.ErrorCode errorCode) {
        return switch (errorCode) {
            case SECRET_NOT_FOUND -> ErrorCode.SECURITY_SECRET_NOT_FOUND;
            case PERMISSION_DENIED -> ErrorCode.SECURITY_FORBIDDEN;
            case BACKEND_UNAVAILABLE -> ErrorCode.SECURITY_SECRET_BACKEND_UNAVAILABLE;
            case ENCRYPTION_FAILED -> ErrorCode.SECURITY_SECRET_ENCRYPTION_FAILED;
            case DECRYPTION_FAILED -> ErrorCode.SECURITY_SECRET_DECRYPTION_FAILED;
            case INVALID_PATH -> ErrorCode.SECURITY_SECRET_INVALID_PATH;
            case QUOTA_EXCEEDED -> ErrorCode.SECURITY_SECRET_QUOTA_EXCEEDED;
            case VERSION_NOT_FOUND -> ErrorCode.SECURITY_SECRET_VERSION_NOT_FOUND;
            case SECRET_EXPIRED -> ErrorCode.SECURITY_SECRET_EXPIRED;
            case ROTATION_FAILED -> ErrorCode.SECURITY_SECRET_ROTATION_FAILED;
            case INVALID_REQUEST -> ErrorCode.VALIDATION_FAILED;
            case INTERNAL_ERROR -> ErrorCode.INTERNAL_ERROR;
        };
    }
}
