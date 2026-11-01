package tech.kayys.wayang.schema.common;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * Configuration for file handling in the system.
 */
public class FileHandling {
    @JsonProperty("allowedExtensions")
    private List<String> allowedExtensions;

    @JsonProperty("maxFileSize")
    private Long maxFileSize;

    @JsonProperty("storagePath")
    private String storagePath;

    @JsonProperty("temporaryStoragePath")
    private String temporaryStoragePath;

    @JsonProperty("cleanupInterval")
    private Long cleanupInterval;

    @JsonProperty("retentionPeriod")
    private Long retentionPeriod;

    @JsonProperty("encryptionEnabled")
    private boolean encryptionEnabled;

    @JsonProperty("metadata")
    private Map<String, String> metadata;

    public FileHandling() {
        // Default constructor for JSON deserialization
    }

    public FileHandling(List<String> allowedExtensions, Long maxFileSize, String storagePath,
                       String temporaryStoragePath, Long cleanupInterval, Long retentionPeriod,
                       boolean encryptionEnabled, Map<String, String> metadata) {
        this.allowedExtensions = allowedExtensions;
        this.maxFileSize = maxFileSize;
        this.storagePath = storagePath;
        this.temporaryStoragePath = temporaryStoragePath;
        this.cleanupInterval = cleanupInterval;
        this.retentionPeriod = retentionPeriod;
        this.encryptionEnabled = encryptionEnabled;
        this.metadata = metadata;
    }

    // Getters
    public List<String> getAllowedExtensions() {
        return allowedExtensions;
    }

    public Long getMaxFileSize() {
        return maxFileSize;
    }

    public String getStoragePath() {
        return storagePath;
    }

    public String getTemporaryStoragePath() {
        return temporaryStoragePath;
    }

    public Long getCleanupInterval() {
        return cleanupInterval;
    }

    public Long getRetentionPeriod() {
        return retentionPeriod;
    }

    public boolean isEncryptionEnabled() {
        return encryptionEnabled;
    }

    public Map<String, String> getMetadata() {
        return metadata;
    }

    // Setters
    public void setAllowedExtensions(List<String> allowedExtensions) {
        this.allowedExtensions = allowedExtensions;
    }

    public void setMaxFileSize(Long maxFileSize) {
        this.maxFileSize = maxFileSize;
    }

    public void setStoragePath(String storagePath) {
        this.storagePath = storagePath;
    }

    public void setTemporaryStoragePath(String temporaryStoragePath) {
        this.temporaryStoragePath = temporaryStoragePath;
    }

    public void setCleanupInterval(Long cleanupInterval) {
        this.cleanupInterval = cleanupInterval;
    }

    public void setRetentionPeriod(Long retentionPeriod) {
        this.retentionPeriod = retentionPeriod;
    }

    public void setEncryptionEnabled(boolean encryptionEnabled) {
        this.encryptionEnabled = encryptionEnabled;
    }

    public void setMetadata(Map<String, String> metadata) {
        this.metadata = metadata;
    }
}