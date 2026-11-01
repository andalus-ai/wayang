package tech.kayys.wayang.schema.api;

import jakarta.inject.Inject;
import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;
import org.jboss.logging.Logger;
import tech.kayys.wayang.schema.catalog.BuiltinSchemaCatalog;
import tech.kayys.wayang.schema.api.dto.*;
import tech.kayys.wayang.schema.validator.*;

@Path("/v1/schema")
@PermitAll
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SchemaApi {

    private static final Logger LOG = Logger.getLogger(SchemaApi.class);

    @Inject
    SchemaValidationService schemaValidationService;

    @Inject
    AgentConfigValidator agentConfigValidator;

    @Inject
    WorkflowValidator workflowValidator;

    @Inject
    PluginConfigValidator pluginConfigValidator;

    @GET
    @Path("/catalog")
    public Response listBuiltinSchemas() {
        List<Map<String, String>> items = BuiltinSchemaCatalog.ids().stream()
                .map(id -> Map.of(
                        "id", id,
                        "path", "/v1/schema/catalog/" + id))
                .toList();
        return Response.ok(Map.of("schemas", items)).build();
    }

    @GET
    @Path("/catalog/{schemaId}")
    public Response getBuiltinSchema(@PathParam("schemaId") String schemaId) {
        String schema = BuiltinSchemaCatalog.get(schemaId);
        if (schema == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of(
                            "message", "Schema not found: " + schemaId,
                            "availableSchemas", BuiltinSchemaCatalog.ids()))
                    .build();
        }
        return Response.ok(Map.of(
                "id", schemaId,
                "schema", schema)).build();
    }

    @POST
    @Path("/validate")
    public Response validateSchema(SchemaValidationRequest request) {
        try {
            ValidationResult result = schemaValidationService.validateSchema(
                request.getSchema(), 
                (java.util.Map<String, Object>) request.getData()
            );

            SchemaValidationResponse response = new SchemaValidationResponse();
            response.setValid(result.isValid());
            response.setMessage(result.getMessage());

            return Response.ok(response).build();
        } catch (Exception e) {
            LOG.error("Error validating schema", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(new SchemaValidationResponse(false, e.getMessage(), null))
                    .build();
        }
    }

    @POST
    @Path("/validate/agent-config")
    public Response validateAgentConfig(java.util.Map<String, Object> agentConfig) {
        try {
            ValidationResult result = agentConfigValidator.validateAgentConfig(agentConfig);

            SchemaValidationResponse response = new SchemaValidationResponse();
            response.setValid(result.isValid());
            response.setMessage(result.getMessage());

            return Response.ok(response).build();
        } catch (Exception e) {
            LOG.error("Error validating agent config", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(new SchemaValidationResponse(false, e.getMessage(), null))
                    .build();
        }
    }

    @POST
    @Path("/validate/workflow")
    public Response validateWorkflow(java.util.Map<String, Object> workflow) {
        try {
            ValidationResult result = workflowValidator.validateWorkflow(workflow);

            SchemaValidationResponse response = new SchemaValidationResponse();
            response.setValid(result.isValid());
            response.setMessage(result.getMessage());

            return Response.ok(response).build();
        } catch (Exception e) {
            LOG.error("Error validating workflow", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(new SchemaValidationResponse(false, e.getMessage(), null))
                    .build();
        }
    }

    @POST
    @Path("/validate/plugin-config")
    public Response validatePluginConfig(java.util.Map<String, Object> pluginConfig) {
        try {
            ValidationResult result = pluginConfigValidator.validatePluginConfig(pluginConfig);

            SchemaValidationResponse response = new SchemaValidationResponse();
            response.setValid(result.isValid());
            response.setMessage(result.getMessage());

            return Response.ok(response).build();
        } catch (Exception e) {
            LOG.error("Error validating plugin config", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(new SchemaValidationResponse(false, e.getMessage(), null))
                    .build();
        }
    }

    @POST
    @Path("/validate-with-rules")
    public Response validateWithRules(SchemaValidationRequest request) {
        try {
            // For now, just do basic schema validation
            ValidationResult result = schemaValidationService.validateSchema(
                request.getSchema(), 
                (java.util.Map<String, Object>) request.getData()
            );

            SchemaValidationResponse response = new SchemaValidationResponse();
            response.setValid(result.isValid());
            response.setMessage(result.getMessage());

            return Response.ok(response).build();
        } catch (Exception e) {
            LOG.error("Error validating with rules", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(new SchemaValidationResponse(false, e.getMessage(), null))
                    .build();
        }
    }
}
