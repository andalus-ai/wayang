package tech.kayys.wayang.schema.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Represents the capabilities of a model.
 */
public class ModelCapabilities {
    @JsonProperty("supportsStreaming")
    private boolean supportsStreaming;

    @JsonProperty("maxTokens")
    private Integer maxTokens;

    @JsonProperty("supportedEncodings")
    private List<String> supportedEncodings;

    @JsonProperty("inputModalities")
    private List<String> inputModalities;

    @JsonProperty("outputModalities")
    private List<String> outputModalities;

    @JsonProperty("contextWindow")
    private Integer contextWindow;

    public ModelCapabilities() {
        // Default constructor for JSON deserialization
    }

    public ModelCapabilities(boolean supportsStreaming, Integer maxTokens, List<String> supportedEncodings,
                           List<String> inputModalities, List<String> outputModalities, Integer contextWindow) {
        this.supportsStreaming = supportsStreaming;
        this.maxTokens = maxTokens;
        this.supportedEncodings = supportedEncodings;
        this.inputModalities = inputModalities;
        this.outputModalities = outputModalities;
        this.contextWindow = contextWindow;
    }

    // Getters
    public boolean isSupportsStreaming() {
        return supportsStreaming;
    }

    public Integer getMaxTokens() {
        return maxTokens;
    }

    public List<String> getSupportedEncodings() {
        return supportedEncodings;
    }

    public List<String> getInputModalities() {
        return inputModalities;
    }

    public List<String> getOutputModalities() {
        return outputModalities;
    }

    public Integer getContextWindow() {
        return contextWindow;
    }

    // Setters
    public void setSupportsStreaming(boolean supportsStreaming) {
        this.supportsStreaming = supportsStreaming;
    }

    public void setMaxTokens(Integer maxTokens) {
        this.maxTokens = maxTokens;
    }

    public void setSupportedEncodings(List<String> supportedEncodings) {
        this.supportedEncodings = supportedEncodings;
    }

    public void setInputModalities(List<String> inputModalities) {
        this.inputModalities = inputModalities;
    }

    public void setOutputModalities(List<String> outputModalities) {
        this.outputModalities = outputModalities;
    }

    public void setContextWindow(Integer contextWindow) {
        this.contextWindow = contextWindow;
    }
}