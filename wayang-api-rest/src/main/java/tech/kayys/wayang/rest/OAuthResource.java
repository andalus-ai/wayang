package tech.kayys.wayang.rest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import io.smallrye.common.annotation.Blocking;
import tech.kayys.wayang.api.ApiException;
import tech.kayys.wayang.api.oauth.OAuthConnection;
import tech.kayys.wayang.api.oauth.OAuthService;

/**
 * Connected accounts. Management is authenticated ({@code /api/v1/oauth}); the provider redirect
 * ({@code /oauth/callback}) lives outside {@code /api} and is authenticated by its single-use state alone.
 */
@Path("/")
@Blocking
public class OAuthResource {

    @Inject
    OAuthConfig config;
    @Inject
    Principals principals;

    private OAuthService svc() {
        return config.service().orElseThrow(
                () -> ApiException.notFound("oauth (needs wayang.secrets.key and wayang.oauth.redirect-uri)"));
    }

    @GET
    @Path("api/v1/oauth/connections")
    @Produces(MediaType.APPLICATION_JSON)
    public List<Map<String, Object>> list() {
        return svc().list(principals.current()).stream().map(OAuthResource::view).toList();
    }

    @POST
    @Path("api/v1/oauth/connections")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response create(Map<String, Object> body) {
        if (body == null) {
            throw ApiException.badRequest("Body required");
        }
        List<String> scopes = body.get("scopes") instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of();
        OAuthConnection c = svc().create(principals.current(), str(body.get("id")), str(body.get("provider")),
                str(body.get("clientId")), str(body.get("clientSecretName")), scopes);
        return Response.status(201).entity(view(c)).build();
    }

    /** @return {@code {"url": ...}}: open it in a browser to grant access */
    @POST
    @Path("api/v1/oauth/connections/{id}/authorize")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> authorize(@PathParam("id") String id) {
        return Map.of("url", svc().authorize(principals.current(), id));
    }

    @DELETE
    @Path("api/v1/oauth/connections/{id}")
    public Response delete(@PathParam("id") String id) {
        svc().delete(principals.current(), id);
        return Response.noContent().build();
    }

    @GET
    @Path("oauth/callback")
    @Produces(MediaType.TEXT_HTML)
    public Response callback(@QueryParam("state") String state, @QueryParam("code") String code, @QueryParam("error") String error) {
        String html;
        int status = 200;
        try {
            OAuthConnection c = svc().callback(state, code, error);
            // nothing from the request is reflected; only the id we stored (restricted to [A-Za-z0-9_-])
            html = page("Connected", "Account connected as \"" + c.id() + "\". You can close this tab.");
        } catch (ApiException e) {
            status = e.code().httpStatus();
            html = page("Not connected", "The connection could not be completed. Start again from Wayang.");
        }
        return Response.status(status).entity(html).header("Cache-Control", "no-store").header("Referrer-Policy", "no-referrer")
                .header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'").build();
    }

    private static String page(String title, String text) {
        return "<!doctype html><meta charset=utf-8><title>" + title + "</title><body style=\"font-family:sans-serif;margin:3rem\"><h1>"
                + title + "</h1><p>" + text + "</p></body>";
    }

    private static String str(Object o) {
        return o instanceof String s ? s : null;
    }

    private static Map<String, Object> view(OAuthConnection c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.id());
        m.put("provider", c.provider());
        m.put("clientId", c.clientId());
        m.put("scopes", c.scopes());
        m.put("status", c.status().name());
        m.put("createdBy", c.createdBy());
        m.put("createdAt", c.createdAt().toString());
        m.put("connectedAt", c.connectedAt() == null ? null : c.connectedAt().toString());
        m.put("secretRef", "${secret:oauth." + c.id() + "}");
        return m;
    }
}
