package tech.kayys.wayang.schema.common;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Configuration for circuit breaker pattern to handle failures gracefully.
 */
public class CircuitBreaker {
    @JsonProperty("enabled")
    private boolean enabled;

    @JsonProperty("failureThreshold")
    private int failureThreshold;

    @JsonProperty("successThreshold")
    private int successThreshold;

    @JsonProperty("timeout")
    private long timeout;

    @JsonProperty("halfOpenAfter")
    private long halfOpenAfter;

    public CircuitBreaker() {
        // Default constructor for JSON deserialization
    }

    public CircuitBreaker(boolean enabled, int failureThreshold, int successThreshold, 
                         long timeout, long halfOpenAfter) {
        this.enabled = enabled;
        this.failureThreshold = failureThreshold;
        this.successThreshold = successThreshold;
        this.timeout = timeout;
        this.halfOpenAfter = halfOpenAfter;
    }

    // Getters
    public boolean isEnabled() {
        return enabled;
    }

    public int getFailureThreshold() {
        return failureThreshold;
    }

    public int getSuccessThreshold() {
        return successThreshold;
    }

    public long getTimeout() {
        return timeout;
    }

    public long getHalfOpenAfter() {
        return halfOpenAfter;
    }

    // Setters
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void setFailureThreshold(int failureThreshold) {
        this.failureThreshold = failureThreshold;
    }

    public void setSuccessThreshold(int successThreshold) {
        this.successThreshold = successThreshold;
    }

    public void setTimeout(long timeout) {
        this.timeout = timeout;
    }

    public void setHalfOpenAfter(long halfOpenAfter) {
        this.halfOpenAfter = halfOpenAfter;
    }
}