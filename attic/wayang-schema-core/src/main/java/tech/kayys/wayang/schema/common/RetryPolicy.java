package tech.kayys.wayang.schema.common;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Configuration for retry policies in case of failures.
 */
public class RetryPolicy {
    @JsonProperty("maximumRetries")
    private int maximumRetries;

    @JsonProperty("retryDelay")
    private long retryDelay;

    @JsonProperty("backoffMultiplier")
    private double backoffMultiplier;

    @JsonProperty("excludedExceptions")
    private List<String> excludedExceptions;

    public RetryPolicy() {
        // Default constructor for JSON deserialization
    }

    public RetryPolicy(int maximumRetries, long retryDelay, double backoffMultiplier, 
                      List<String> excludedExceptions) {
        this.maximumRetries = maximumRetries;
        this.retryDelay = retryDelay;
        this.backoffMultiplier = backoffMultiplier;
        this.excludedExceptions = excludedExceptions;
    }

    // Getters
    public int getMaximumRetries() {
        return maximumRetries;
    }

    public long getRetryDelay() {
        return retryDelay;
    }

    public double getBackoffMultiplier() {
        return backoffMultiplier;
    }

    public List<String> getExcludedExceptions() {
        return excludedExceptions;
    }

    // Setters
    public void setMaximumRetries(int maximumRetries) {
        this.maximumRetries = maximumRetries;
    }

    public void setRetryDelay(long retryDelay) {
        this.retryDelay = retryDelay;
    }

    public void setBackoffMultiplier(double backoffMultiplier) {
        this.backoffMultiplier = backoffMultiplier;
    }

    public void setExcludedExceptions(List<String> excludedExceptions) {
        this.excludedExceptions = excludedExceptions;
    }
}