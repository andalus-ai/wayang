package tech.kayys.wayang.schema;

import com.fasterxml.jackson.annotation.JsonProperty;
import tech.kayys.wayang.schema.common.CircuitBreaker;
import tech.kayys.wayang.schema.common.RateLimit;
import tech.kayys.wayang.schema.common.RetryPolicy;
import tech.kayys.wayang.schema.workflow.WorkflowConfig;

import java.util.HashMap;
import java.util.Map;

/**
 * WayangConfig — Global configuration for a WayangDefinition.
 * <p>
 * Consolidates workflow-level config, error handling, resilience patterns,
 * and custom extension configuration into a single object.
 */
public class WayangConfig {

    @JsonProperty("workflowConfig")
    private WorkflowConfig workflowConfig;

    @JsonProperty("retryPolicy")
    private RetryPolicy retryPolicy;

    @JsonProperty("circuitBreaker")
    private CircuitBreaker circuitBreaker;

    @JsonProperty("rateLimit")
    private RateLimit rateLimit;

    @JsonProperty("globalTimeout")
    private long globalTimeout; // milliseconds

    @JsonProperty("observability")
    private ObservabilityConfig observability;

    @JsonProperty("custom")
    private Map<String, Object> custom = new HashMap<>();

    public WayangConfig() {
    }

    // Getters
    public WorkflowConfig getWorkflowConfig() {
        return workflowConfig;
    }

    public RetryPolicy getRetryPolicy() {
        return retryPolicy;
    }

    public CircuitBreaker getCircuitBreaker() {
        return circuitBreaker;
    }

    public RateLimit getRateLimit() {
        return rateLimit;
    }

    public long getGlobalTimeout() {
        return globalTimeout;
    }

    public ObservabilityConfig getObservability() {
        return observability;
    }

    public Map<String, Object> getCustom() {
        return custom;
    }

    // Setters
    public void setWorkflowConfig(WorkflowConfig workflowConfig) {
        this.workflowConfig = workflowConfig;
    }

    public void setRetryPolicy(RetryPolicy retryPolicy) {
        this.retryPolicy = retryPolicy;
    }

    public void setCircuitBreaker(CircuitBreaker circuitBreaker) {
        this.circuitBreaker = circuitBreaker;
    }

    public void setRateLimit(RateLimit rateLimit) {
        this.rateLimit = rateLimit;
    }

    public void setGlobalTimeout(long globalTimeout) {
        this.globalTimeout = globalTimeout;
    }

    public void setObservability(ObservabilityConfig observability) {
        this.observability = observability;
    }

    public void setCustom(Map<String, Object> custom) {
        this.custom = custom;
    }
}
