package tech.kayys.wayang.schema;

import com.fasterxml.jackson.annotation.JsonProperty;
import tech.kayys.wayang.schema.canvas.CanvasData;
import tech.kayys.wayang.schema.workflow.WorkflowSpec;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * WayangSpec — The unified specification for an agentic AI workflow.
 * <p>
 * This is the single source of truth that contains everything needed
 * to design, validate, and execute a Wayang workflow:
 * <ul>
 * <li>Canvas: visual representation (nodes, edges, layout)</li>
 * <li>Workflow: logical execution flow (connections, variables, config)</li>
 * <li>Agents: AI agent definitions (LLM, tools, guardrails)</li>
 * <li>Deployment: runtime configuration (environment, replicas)</li>
 * <li>Config: global settings (error handling, retry, circuit breaker)</li>
 * </ul>
 * <p>
 * Stored as a single JSONB column in the {@code wayang_definitions} table.
 */
public class WayangSpec {

    @JsonProperty("specVersion")
    private String specVersion = "1.0.0";

    @JsonProperty("canvas")
    private CanvasData canvas;

    @JsonProperty("workflow")
    private WorkflowSpec workflow;

    @JsonProperty("agents")
    private List<Map<String, Object>> agents = new ArrayList<>();

    @JsonProperty("deployment")
    private Map<String, Object> deployment;

    @JsonProperty("config")
    private WayangConfig config;

    @JsonProperty("extensions")
    private Map<String, Object> extensions = new HashMap<>();

    public WayangSpec() {
        // Default constructor for JSON deserialization
    }

    public WayangSpec(CanvasData canvas, WorkflowSpec workflow, List<Map<String, Object>> agents,
            Map<String, Object> deployment, WayangConfig config) {
        this.canvas = canvas;
        this.workflow = workflow;
        this.agents = agents != null ? agents : new ArrayList<>();
        this.deployment = deployment;
        this.config = config;
    }

    // Getters
    public String getSpecVersion() {
        return specVersion;
    }

    public CanvasData getCanvas() {
        return canvas;
    }

    public WorkflowSpec getWorkflow() {
        return workflow;
    }

    public List<Map<String, Object>> getAgents() {
        return agents;
    }

    public Map<String, Object> getDeployment() {
        return deployment;
    }

    public WayangConfig getConfig() {
        return config;
    }

    public Map<String, Object> getExtensions() {
        return extensions;
    }

    // Setters
    public void setSpecVersion(String specVersion) {
        this.specVersion = specVersion;
    }

    public void setCanvas(CanvasData canvas) {
        this.canvas = canvas;
    }

    public void setWorkflow(WorkflowSpec workflow) {
        this.workflow = workflow;
    }

    public void setAgents(List<Map<String, Object>> agents) {
        this.agents = agents;
    }

    public void setDeployment(Map<String, Object> deployment) {
        this.deployment = deployment;
    }

    public void setConfig(WayangConfig config) {
        this.config = config;
    }

    public void setExtensions(Map<String, Object> extensions) {
        this.extensions = extensions;
    }
}
