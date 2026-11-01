package tech.kayys.wayang.control.dto;

/**
 * Specific capability provided to an AI agent.
 */
public class AgentCapability {
    public String name;
    public CapabilityType type;
    public boolean enabled;
    public String configuration; // JSON schema or parameters
}
