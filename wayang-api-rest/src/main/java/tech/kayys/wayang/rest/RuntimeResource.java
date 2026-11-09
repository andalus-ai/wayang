package tech.kayys.wayang.rest;

import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import io.smallrye.common.annotation.Blocking;
import tech.kayys.wayang.api.run.RunView;
import tech.kayys.wayang.api.run.TaskView;
import tech.kayys.wayang.api.WayangApi;

/** Runs, human tasks, messages, authoring aids and admin operations. */
@Path("/api/v1")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Blocking
public class RuntimeResource {

    public record StartRun(String processId, Integer version, Map<String, Object> input) {
    }

    public record Expression(String expression, Map<String, Object> variables) {
    }

    public record DecisionTest(Map<String, Object> document, Map<String, Object> inputs) {
    }

    public record Message(String name, String correlationKey, Map<String, Object> payload) {
    }

    @Inject
    WayangApi api;
    @Inject
    Principals principals;

    @POST
    @Path("runs")
    public Response start(StartRun body) {
        if (body == null || body.processId() == null) {
            throw tech.kayys.wayang.api.ApiException.badRequest("processId is required");
        }
        RunView r = api.startRun(principals.current(), body.processId(), body.version(), body.input());
        return Response.status(201).entity(r).build();
    }

    @GET
    @Path("runs")
    public List<RunView> runs(@QueryParam("processId") String processId, @QueryParam("limit") @DefaultValue("50") int limit) {
        return api.listRuns(principals.current(), processId, limit);
    }

    @GET
    @Path("runs/{id}")
    public RunView run(@PathParam("id") String id) {
        return api.getRun(principals.current(), id);
    }

    @POST
    @Path("runs/{id}/cancel")
    public Response cancel(@PathParam("id") String id) {
        api.cancelRun(principals.current(), id);
        return Response.noContent().build();
    }

    @GET
    @Path("tasks")
    public List<TaskView> tasks(@QueryParam("limit") @DefaultValue("100") int limit) {
        return api.listTasks(principals.current(), limit);
    }

    @POST
    @Path("tasks/{token}/complete")
    public Response complete(@PathParam("token") String token, Map<String, Object> outputs) {
        api.completeTask(principals.current(), token, outputs);
        return Response.noContent().build();
    }

    @POST
    @Path("messages")
    public Map<String, String> message(Message m) {
        if (m == null) {
            throw tech.kayys.wayang.api.ApiException.badRequest("Missing body");
        }
        return Map.of("result", api.sendMessage(principals.current(), m.name(), m.correlationKey(), m.payload()));
    }

    @POST
    @Path("tools/expression")
    public Map<String, Object> expression(Expression e) {
        Object v = api.evalExpression(principals.current(), e == null ? null : e.expression(), e == null ? null : e.variables());
        return java.util.Collections.singletonMap("value", v);
    }

    @POST
    @Path("tools/decision-test")
    public Map<String, Object> decisionTest(DecisionTest t) {
        return api.testDecision(principals.current(), t == null ? null : t.document(), t == null ? null : t.inputs());
    }

    @POST
    @Path("admin/timers/fire")
    public Map<String, Integer> fire() {
        return Map.of("fired", api.fireTimers(principals.current()));
    }
}
