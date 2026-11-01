package tech.kayys.wayang.schema;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Observability configuration — logging, tracing, and metrics settings.
 */
public class ObservabilityConfig {

    @JsonProperty("tracingEnabled")
    private boolean tracingEnabled = true;

    @JsonProperty("metricsEnabled")
    private boolean metricsEnabled = true;

    @JsonProperty("logLevel")
    private String logLevel = "INFO";

    @JsonProperty("tracingEndpoint")
    private String tracingEndpoint;

    public ObservabilityConfig() {
    }

    // Getters
    public boolean isTracingEnabled() {
        return tracingEnabled;
    }

    public boolean isMetricsEnabled() {
        return metricsEnabled;
    }

    public String getLogLevel() {
        return logLevel;
    }

    public String getTracingEndpoint() {
        return tracingEndpoint;
    }

    // Setters
    public void setTracingEnabled(boolean tracingEnabled) {
        this.tracingEnabled = tracingEnabled;
    }

    public void setMetricsEnabled(boolean metricsEnabled) {
        this.metricsEnabled = metricsEnabled;
    }

    public void setLogLevel(String logLevel) {
        this.logLevel = logLevel;
    }

    public void setTracingEndpoint(String tracingEndpoint) {
        this.tracingEndpoint = tracingEndpoint;
    }
}
