package tech.kayys.wayang.control.dto;

import java.util.Map;

public /**
        * LLM Configuration
        */
class LLMConfig {
    public String provider; // openai, anthropic, azure, etc.
    public String model; // gpt-4, claude-3, etc.
    public double temperature;
    public int maxTokens;
    public String systemPrompt;
    public Map<String, Object> parameters;
}
