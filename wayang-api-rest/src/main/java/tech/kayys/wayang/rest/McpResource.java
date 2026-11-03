package tech.kayys.wayang.rest;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import io.smallrye.common.annotation.Blocking;
import tech.kayys.wayang.api.ApiException;
import tech.kayys.wayang.api.mcp.McpServerDef;
import tech.kayys.wayang.api.mcp.McpService;
import tech.kayys.wayang.mcp.OpenApiToMcp;

/** Generate MCP servers from OpenAPI/Swagger and serve them. Tenant comes from the authenticated principal. */
@Path("/api/v1/mcp")
@Blocking
public class McpResource {

    @Inject
    McpConfig config;
    @Inject
    Principals principals;

    @POST
    @Path("servers/preview")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> preview(Map<String, Object> body) {
        return generated(config.service().preview(principals.current(), request(body)));
    }

    @POST
    @Path("servers")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response create(Map<String, Object> body) {
        if (body == null || !(body.get("id") instanceof String id)) {
            throw ApiException.badRequest("id is required");
        }
        return Response.status(201).entity(view(config.service().create(principals.current(), id, request(body)), true)).build();
    }

    @GET
    @Path("servers")
    @Produces(MediaType.APPLICATION_JSON)
    public List<Map<String, Object>> list() {
        return config.service().list(principals.current()).stream().map(d -> view(d, false)).toList();
    }

    @GET
    @Path("servers/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> get(@PathParam("id") String id) {
        return view(config.service().get(principals.current(), id), true);
    }

    @POST
    @Path("servers/{id}/refresh")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> refresh(@PathParam("id") String id) {
        return view(config.service().refresh(principals.current(), id), true);
    }

    @DELETE
    @Path("servers/{id}")
    public Response delete(@PathParam("id") String id) {
        config.service().delete(principals.current(), id);
        return Response.noContent().build();
    }

    /** The MCP endpoint: Streamable HTTP, JSON responses only, stateless. */
    @POST
    @Path("servers/{id}/mcp")
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.APPLICATION_JSON)
    public Response rpc(@PathParam("id") String id, String body) {
        String out = config.service().rpc(principals.current(), id, body);
        return out == null ? Response.accepted().build() : Response.ok(out).build();
    }

    /** Published processes that opted in (studio.mcp.expose) as one MCP server. */
    @POST
    @Path("processes")
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.APPLICATION_JSON)
    public Response processes(String body) {
        String out = config.processes().rpc(principals.current(), body);
        return out == null ? Response.accepted().build() : Response.ok(out).build();
    }

    @GET
    @Path("processes")
    public Response processesNoStream() {
        return Response.status(405).header("Allow", "POST").build();
    }

    /** No server-initiated stream: tells clients to use POST only. */
    @GET
    @Path("servers/{id}/mcp")
    public Response noStream(@PathParam("id") String id) {
        return Response.status(405).header("Allow", "POST").build();
    }

    // ------------------------------------------------------------------ mapping

    @SuppressWarnings("unchecked")
    private static McpService.Request request(Map<String, Object> b) {
        if (b == null) {
            throw ApiException.badRequest("Body required");
        }
        return new McpService.Request(str(b.get("spec")), str(b.get("specUrl")), str(b.get("baseUrl")),
                strMap(b.get("headers")), strMap(b.get("query")), strSet(b.get("methods")), strSet(b.get("includeTags")),
                strSet(b.get("excludeTags")), strList(b.get("includePaths")), strList(b.get("excludePaths")),
                b.get("maxTools") instanceof Number n ? n.intValue() : null, str(b.get("namePrefix")));
    }

    private static String str(Object o) {
        return o instanceof String s ? s : null;
    }

    private static Map<String, String> strMap(Object o) {
        Map<String, String> out = new LinkedHashMap<>();
        if (o instanceof Map<?, ?> m) {
            m.forEach((k, v) -> out.put(String.valueOf(k), String.valueOf(v)));
        }
        return out;
    }

    private static List<String> strList(Object o) {
        return o instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of();
    }

    private static Set<String> strSet(Object o) {
        return new LinkedHashSet<>(strList(o));
    }

    private static Map<String, Object> generated(OpenApiToMcp.Result r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", r.title());
        m.put("version", r.version());
        m.put("baseUrl", r.baseUrl());
        m.put("tools", r.tools().stream().map(t -> t.toMcp()).toList());
        m.put("warnings", r.warnings());
        m.put("security", r.security());
        return m;
    }

    private static Map<String, Object> view(McpServerDef d, boolean withTools) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.id());
        m.put("title", d.title());
        m.put("version", d.version());
        m.put("baseUrl", d.baseUrl());
        m.put("sourceUrl", d.sourceUrl());
        m.put("toolCount", d.tools().size());
        m.put("headers", d.headers()); // templates: secrets appear as ${secret:NAME}, never as values
        m.put("warnings", d.warnings());
        m.put("security", d.security());
        m.put("createdBy", d.createdBy());
        m.put("updatedAt", d.updatedAt().toString());
        m.put("endpoint", "/api/v1/mcp/servers/" + d.id() + "/mcp");
        if (withTools) {
            m.put("tools", d.tools().stream().map(t -> t.toMcp()).toList());
        }
        return m;
    }
}
