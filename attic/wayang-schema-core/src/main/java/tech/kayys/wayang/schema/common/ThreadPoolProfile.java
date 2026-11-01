package tech.kayys.wayang.schema.common;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Configuration for thread pool profiles.
 */
public class ThreadPoolProfile {
    @JsonProperty("id")
    private String id;

    @JsonProperty("poolSize")
    private int poolSize;

    @JsonProperty("maxPoolSize")
    private int maxPoolSize;

    @JsonProperty("keepAliveTime")
    private long keepAliveTime;

    @JsonProperty("timeUnit")
    private String timeUnit;

    @JsonProperty("queueSize")
    private int queueSize;

    @JsonProperty("rejectedExecutionHandler")
    private String rejectedExecutionHandler;

    public ThreadPoolProfile() {
        // Default constructor for JSON deserialization
    }

    public ThreadPoolProfile(String id, int poolSize, int maxPoolSize, long keepAliveTime,
                            String timeUnit, int queueSize, String rejectedExecutionHandler) {
        this.id = id;
        this.poolSize = poolSize;
        this.maxPoolSize = maxPoolSize;
        this.keepAliveTime = keepAliveTime;
        this.timeUnit = timeUnit;
        this.queueSize = queueSize;
        this.rejectedExecutionHandler = rejectedExecutionHandler;
    }

    // Getters
    public String getId() {
        return id;
    }

    public int getPoolSize() {
        return poolSize;
    }

    public int getMaxPoolSize() {
        return maxPoolSize;
    }

    public long getKeepAliveTime() {
        return keepAliveTime;
    }

    public String getTimeUnit() {
        return timeUnit;
    }

    public int getQueueSize() {
        return queueSize;
    }

    public String getRejectedExecutionHandler() {
        return rejectedExecutionHandler;
    }

    // Setters
    public void setId(String id) {
        this.id = id;
    }

    public void setPoolSize(int poolSize) {
        this.poolSize = poolSize;
    }

    public void setMaxPoolSize(int maxPoolSize) {
        this.maxPoolSize = maxPoolSize;
    }

    public void setKeepAliveTime(long keepAliveTime) {
        this.keepAliveTime = keepAliveTime;
    }

    public void setTimeUnit(String timeUnit) {
        this.timeUnit = timeUnit;
    }

    public void setQueueSize(int queueSize) {
        this.queueSize = queueSize;
    }

    public void setRejectedExecutionHandler(String rejectedExecutionHandler) {
        this.rejectedExecutionHandler = rejectedExecutionHandler;
    }
}