package tech.kayys.wayang.rest;

import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import io.smallrye.common.annotation.Blocking;
import tech.kayys.wayang.api.ui.UiSurfaceValidator;

/**
 * Checks a declarative UI surface an agent or process produced ({@code POST /api/v1/ui/surfaces/validate}). Stateless: nothing is stored,
 * and any signed-in user may call it. The answer is {@code {ok, problems, surface}} where {@code surface} is the rebuilt, safe copy (null
 * when {@code ok} is false). A surface that fails is a normal answer (200), not an error, so a tool can show the problems.
 */
@Path("/api/v1/ui/surfaces")
@Blocking
public class UiSurfaceResource {

    @Inject
    Principals principals;

    @POST
    @Path("/validate")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> validate(Object body) {
        principals.current(); // must be signed in
        var r = UiSurfaceValidator.validate(body);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", r.ok());
        out.put("problems", r.problems());
        out.put("surface", r.surface());
        return out;
    }
}
