package tech.kayys.wayang.schema.api;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import tech.kayys.wayang.eip.schema.EIPSchema;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
public class SchemaApiDynamicGenerationTest {

    @Test
    public void testDynamicSplitterSchema() {
        // Fetch the Splitter schema from the catalog API
        given()
                .when().get("/v1/schema/catalog/" + EIPSchema.EIP_SPLITTER)
                .then()
                .statusCode(200)
                .body("id", notNullValue())
                .body("schema", notNullValue())
                // Verify the schema string contains the DTO properties we defined in
                // SplitterDto
                .body("schema", containsString("\"expression\""))
                .body("schema", containsString("\"strategy\""))
                .body("schema", containsString("\"parallel\""))
                .body("schema", containsString("\"batchSize\"")); // We recently added this!
    }

    @Test
    public void testDynamicDeadLetterChannelSchema() {
        // Fetch the DeadLetterChannel schema
        given()
                .when().get("/v1/schema/catalog/" + "dead-letter-channel")
                .then()
                .statusCode(200)
                .body("schema", containsString("\"channelName\""))
                .body("schema", containsString("\"retentionDays\"")); // Verified property mismatch fix
    }

    @Test
    public void testDynamicOrchestratorAgentSchema() {
        given()
                .when().get("/v1/schema/catalog/" + "agent-orchestrator")
                .then()
                .statusCode(200)
                .body("id", notNullValue())
                .body("schema", notNullValue())
                .body("schema", containsString("\"goal\""))
                .body("schema", containsString("\"strategy\""))
                .body("schema", containsString("\"maxIterations\""))
                .body("schema", containsString("\"maxDelegations\""))
                .body("schema", containsString("\"maxLatencyMs\""))
                .body("schema", containsString("\"maxAgentLatencyMs\""))
                .body("schema", containsString("\"maxRetriesPerDelegation\""));
    }

    @Test
    public void testDynamicAnalyticAgentSchema() {
        given()
                .when().get("/v1/schema/catalog/" + "agent-analytic")
                .then()
                .statusCode(200)
                .body("id", notNullValue())
                .body("schema", notNullValue())
                .body("schema", containsString("\"goal\""))
                .body("schema", containsString("\"criteria\""))
                .body("schema", containsString("\"preferredProvider\""));
    }

    @Test
    public void testDynamicHumanTaskSchema() {
        given()
                .when().get("/v1/schema/catalog/" + "hitl-human-task")
                .then()
                .statusCode(200)
                .body("id", notNullValue())
                .body("schema", notNullValue())
                // Core assignment fields
                .body("schema", containsString("\"assignTo\""))
                .body("schema", containsString("\"assigneeType\""))
                .body("schema", containsString("\"taskType\""))
                .body("schema", containsString("\"title\""))
                .body("schema", containsString("\"priority\""))
                // Due date fields
                .body("schema", containsString("\"dueInHours\""))
                .body("schema", containsString("\"dueInDays\""))
                // Nested configs
                .body("schema", containsString("\"escalationConfig\""))
                .body("schema", containsString("\"notificationConfig\""));
    }

    @Test
    public void testDynamicSubWorkflowSchema() {
        given()
                .when().get("/v1/schema/catalog/" + "sub-workflow")
                .then()
                .statusCode(200)
                .body("id", notNullValue())
                .body("schema", notNullValue())
                .body("schema", containsString("\"projectId\""))
                .body("schema", containsString("\"invokeMode\""))
                .body("schema", containsString("\"inputs\""))
                .body("schema", containsString("\"parameters\""))
                .body("schema", containsString("\"inputBindings\""))
                .body("schema", containsString("\"exposedOutput\""))
                .body("schema", containsString("\"wayangSpec\""))
                .body("schema", containsString("\"workflowSpec\""))
                .body("schema", containsString("\"maxDepth\""));
    }

    @Test
    public void testWorkflowAliasAndWorkflowSpecSchemaAreEquivalent() {
        String workflowSchema = given()
                .when().get("/v1/schema/catalog/workflow")
                .then()
                .statusCode(200)
                .body("id", equalTo("workflow"))
                .extract()
                .path("schema");

        given()
                .when().get("/v1/schema/catalog/workflow-spec")
                .then()
                .statusCode(200)
                .body("id", equalTo("workflow-spec"))
                .body("schema", equalTo(workflowSchema));
    }
}
