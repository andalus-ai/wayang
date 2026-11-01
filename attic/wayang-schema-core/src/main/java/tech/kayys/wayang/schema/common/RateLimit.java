package tech.kayys.wayang.schema.common;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Represents rate limiting configuration for API calls or operations.
 */
public class RateLimit {
    @JsonProperty("requests")
    private int requests;

    @JsonProperty("period")
    private int period;

    @JsonProperty("unit")
    private String unit;

    public RateLimit() {
        // Default constructor for JSON deserialization
    }

    public RateLimit(int requests, int period, String unit) {
        this.requests = requests;
        this.period = period;
        this.unit = unit;
    }

    // Getters
    public int getRequests() {
        return requests;
    }

    public int getPeriod() {
        return period;
    }

    public String getUnit() {
        return unit;
    }

    // Setters
    public void setRequests(int requests) {
        this.requests = requests;
    }

    public void setPeriod(int period) {
        this.period = period;
    }

    public void setUnit(String unit) {
        this.unit = unit;
    }
}