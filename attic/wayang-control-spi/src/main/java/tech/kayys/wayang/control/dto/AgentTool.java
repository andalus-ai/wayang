package tech.kayys.wayang.control.dto;

import java.util.Map;

/**
 * Tool definition that can be used by an AI agent.
 */
public class AgentTool {
    public String toolId;
    public String name;
    public String description;
    public ToolType type;
    public String endpointUrl;
    public boolean enabled = true;
    public String serverId; // For MCP
    public String parametersSchema; // For Functions (JSON Schema)
    public Map<String, String> configuration;
    public Map<String, String> metadata;
}
