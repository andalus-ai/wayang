package tech.kayys.wayang.control.dto.agent;

import java.util.List;

import tech.kayys.wayang.agent.AgentType;
import tech.kayys.wayang.control.dto.AgentCapability;
import tech.kayys.wayang.control.dto.AgentTool;
import tech.kayys.wayang.control.dto.LLMConfig;
import tech.kayys.wayang.control.dto.MemoryConfig;

public record CreateAgentRequest(
                String agentName,
                String description,
                AgentType agentType,
                LLMConfig llmConfig,
                List<AgentCapability> capabilities,
                List<AgentTool> tools,
                MemoryConfig memoryConfig,
                List<Guardrail> guardrails) {
}
