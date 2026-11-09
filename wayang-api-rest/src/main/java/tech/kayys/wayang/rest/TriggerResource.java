package tech.kayys.wayang.rest;

import java.util.LinkedHashMap;
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
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.core.Response;

import io.smallrye.common.annotation.Blocking;
import tech.kayys.wayang.api.ApiException;
import tech.kayys.wayang.api.trigger.Trigger;
import tech.kayys.wayang.api.trigger.TriggerService;

/**
 * Manage triggers (authenticated) and receive webhooks (authenticated by the trigger's own secret, so it lives
 * outside {@code /api} where the bearer-token policy applies).
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Blocking
public class TriggerResource {

    @Inject
    TriggerService triggers;
    @Inject
    Principals principals;

    @GET
    @Path("api/v1/triggers")
    public List<Map<String, Object>> list() {
        return triggers.list(principals.current()).stream().map(TriggerResource::view).toList();
    }

    @POST
    @Path("api/v1/triggers")
    public Response create(Map<String, Object> body) {
        Trigger.Kind kind;
        try {
            kind = Trigger.Kind.valueOf(String.valueOf(body.get("kind")).toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("kind must be WEBHOOK or POLL");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> config = body.get("config") instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
        var created = triggers.create(principals.current(), String.valueOf(body.get("id")), kind, String.valueOf(body.get("processId")), config);
        Map<String, Object> out = view(created.trigger());
        if (created.webhookSecret() != null) {
            out.put("webhookSecret", created.webhookSecret()); // shown once
            out.put("webhookPath", "/hooks/" + created.trigger().tenant() + "/" + created.trigger().id());
        }
        return Response.status(201).entity(out).build();
    }

    @PUT
    @Path("api/v1/triggers/{id}/enabled")
    public Response enable(@PathParam("id") String id, Map<String, Object> body) {
        triggers.setEnabled(principals.current(), id, Boolean.TRUE.equals(body.get("enabled")));
        return Response.noContent().build();
    }

    @DELETE
    @Path("api/v1/triggers/{id}")
    public Response delete(@PathParam("id") String id) {
        triggers.delete(principals.current(), id);
        return Response.noContent().build();
    }

    /**
     * Webhook receiver. Authenticated by the trigger itself: its shared secret in {@code X-Wayang-Secret} (not the URL,
     * so it stays out of access logs) or the provider's signature over the raw body (Slack, WhatsApp).
     */
    @POST
    @Path("hooks/{tenant}/{id}")
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.WILDCARD)
    public Response hookPost(@PathParam("tenant") String tenant, @PathParam("id") String id, @Context HttpHeaders headers,
            @Context UriInfo uri, byte[] body) {
        return answer(triggers.receive(tenant, id, "POST", query(uri), flat(headers), body));
    }

    /** WhatsApp (Meta) verifies the endpoint with a GET. */
    @GET
    @Path("hooks/{tenant}/{id}")
    @Produces(MediaType.WILDCARD)
    public Response hookGet(@PathParam("tenant") String tenant, @PathParam("id") String id, @Context HttpHeaders headers,
            @Context UriInfo uri) {
        return answer(triggers.receive(tenant, id, "GET", query(uri), flat(headers), null));
    }

    private static Response answer(TriggerService.Reception r) {
        return Response.status(r.status()).entity(r.body()).type(r.json() ? MediaType.APPLICATION_JSON : MediaType.TEXT_PLAIN).build();
    }

    private static Map<String, String> flat(HttpHeaders headers) {
        Map<String, String> out = new LinkedHashMap<>();
        headers.getRequestHeaders().forEach((k, v) -> {
            if (v != null && !v.isEmpty()) {
                out.put(k, v.get(0));
            }
        });
        return out;
    }

    private static Map<String, String> query(UriInfo uri) {
        Map<String, String> out = new LinkedHashMap<>();
        uri.getQueryParameters().forEach((k, v) -> {
            if (v != null && !v.isEmpty()) {
                out.put(k, v.get(0));
            }
        });
        return out;
    }

    private static Map<String, Object> view(Trigger t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.id());
        m.put("kind", t.kind().name());
        m.put("processId", t.processId());
        m.put("enabled", t.enabled());
        m.put("config", t.config());
        m.put("lastPolledAt", t.lastPolledAt() == null ? null : t.lastPolledAt().toString());
        m.put("lastError", t.lastError());
        m.put("consecutiveFailures", t.consecutiveFailures());
        return m;
    }
}
