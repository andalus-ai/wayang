package tech.kayys.wayang.schema.workflow;

import com.fasterxml.jackson.annotation.JsonProperty;
import tech.kayys.wayang.schema.common.RateLimit;
import tech.kayys.wayang.schema.common.RetryPolicy;
import tech.kayys.wayang.schema.common.CircuitBreaker;

/**
 * Configuration options for a workflow.
 */
public class WorkflowConfig {
    @JsonProperty("timeout")
    private long timeout;

    @JsonProperty("maxRetries")
    private int maxRetries;

    @JsonProperty("rateLimit")
    private RateLimit rateLimit;

    @JsonProperty("retryPolicy")
    private RetryPolicy retryPolicy;

    @JsonProperty("circuitBreaker")
    private CircuitBreaker circuitBreaker;

    @JsonProperty("parallelExecution")
    private boolean parallelExecution;

    @JsonProperty("errorHandlingStrategy")
    private String errorHandlingStrategy;

    public WorkflowConfig() {
        // Default constructor for JSON deserialization
    }

    public WorkflowConfig(long timeout, int maxRetries, RateLimit rateLimit, 
                         RetryPolicy retryPolicy, CircuitBreaker circuitBreaker, 
                         boolean parallelExecution, String errorHandlingStrategy) {
        this.timeout = timeout;
        this.maxRetries = maxRetries;
        this.rateLimit = rateLimit;
        this.retryPolicy = retryPolicy;
        this.circuitBreaker = circuitBreaker;
        this.parallelExecution = parallelExecution;
        this.errorHandlingStrategy = errorHandlingStrategy;
    }

    // Getters
    public long getTimeout() {
        return timeout;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public RateLimit getRateLimit() {
        return rateLimit;
    }

    public RetryPolicy getRetryPolicy() {
        return retryPolicy;
    }

    public CircuitBreaker getCircuitBreaker() {
        return circuitBreaker;
    }

    public boolean isParallelExecution() {
        return parallelExecution;
    }

    public String getErrorHandlingStrategy() {
        return errorHandlingStrategy;
    }

    // Setters
    public void setTimeout(long timeout) {
        this.timeout = timeout;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public void setRateLimit(RateLimit rateLimit) {
        this.rateLimit = rateLimit;
    }

    public void setRetryPolicy(RetryPolicy retryPolicy) {
        this.retryPolicy = retryPolicy;
    }

    public void setCircuitBreaker(CircuitBreaker circuitBreaker) {
        this.circuitBreaker = circuitBreaker;
    }

    public void setParallelExecution(boolean parallelExecution) {
        this.parallelExecution = parallelExecution;
    }

    public void setErrorHandlingStrategy(String errorHandlingStrategy) {
        this.errorHandlingStrategy = errorHandlingStrategy;
    }
}