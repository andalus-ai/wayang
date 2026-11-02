package tech.kayys.wayang.rest;

import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import io.smallrye.common.annotation.Blocking;
import tech.kayys.wayang.api.ApiException;
import tech.kayys.wayang.api.secret.SecretService;

/**
 * Write-only secrets, tenant taken from the authenticated principal (never from the request). There is no
 * endpoint that returns a value; reference one from a node as {@code ${secret:NAME}}.
 */
@Path("/api/v1/secrets")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Blocking
public class SecretResource {

    @Inject
    SecretsConfig config;
    @Inject
    Principals principals;

    private SecretService svc() {
        return config.service().orElseThrow(() -> ApiException.notFound("secrets (wayang.secrets.key is not configured)"));
    }

    @GET
    public List<SecretService.Meta> list() {
        return svc().list(principals.current());
    }

    @PUT
    @Path("{name}")
    public SecretService.Meta put(@PathParam("name") String name, Map<String, Object> body) {
        Object v = body == null ? null : body.get("value");
        return svc().put(principals.current(), name, v instanceof String s ? s : null);
    }

    @DELETE
    @Path("{name}")
    public Response delete(@PathParam("name") String name) {
        svc().delete(principals.current(), name);
        return Response.noContent().build();
    }

    /** Which master key the tenant's secrets are sealed under: how far a rotation has got. */
    @GET
    @Path("keys")
    public Map<String, Object> keys() {
        return svc().keyStatus(principals.current());
    }

    /** Picks up a rotated keyring file or Vault secret without a restart; refuses a ring that drops a key still in use. Admin. */
    @POST
    @Path("keys/reload")
    public Map<String, Object> reloadKeys() {
        return svc().reloadKeys(principals.current(), config.reloadSource());
    }

    /**
     * An encrypted copy of all the tenant's secrets (see SecretArchive). Off unless {@code wayang.secrets.archive-enabled=true}; admin
     * only; POST so that it is audited. The passphrase travels in the body, never in a URL.
     */
    @POST
    @Path("archive/export")
    public Map<String, Object> archiveExport(Map<String, Object> body) {
        archiveOn();
        return svc().exportArchive(principals.current(), body != null && body.get("passphrase") instanceof String s ? s : null);
    }

    @POST
    @Path("archive/import")
    @SuppressWarnings("unchecked")
    public SecretService.ImportResult archiveImport(Map<String, Object> body) {
        archiveOn();
        if (body == null || !(body.get("archive") instanceof Map<?, ?> a)) {
            throw ApiException.badRequest("Body must be {archive: {...}, passphrase: \"...\", overwrite: false}");
        }
        return svc().importArchive(principals.current(), body.get("passphrase") instanceof String s ? s : null, (Map<String, Object>) a,
                Boolean.TRUE.equals(body.get("overwrite")));
    }

    private void archiveOn() {
        if (!config.archiveEnabled()) {
            throw ApiException.notFound("secrets archive (wayang.secrets.archive-enabled is not set)");
        }
    }

    @POST
    @Path("rotate")
    public Map<String, Object> rotate() {
        return Map.of("resealed", svc().rotate(principals.current()));
    }
}
