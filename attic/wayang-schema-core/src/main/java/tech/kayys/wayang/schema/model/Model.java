package tech.kayys.wayang.schema.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import tech.kayys.wayang.schema.common.Metadata;
import java.util.Map;

/**
 * Represents a machine learning or AI model configuration.
 */
public class Model {
    @JsonProperty("metadata")
    private Metadata metadata;

    @JsonProperty("provider")
    private String provider;

    @JsonProperty("modelName")
    private String modelName;

    @JsonProperty("version")
    private String version;

    @JsonProperty("endpoint")
    private String endpoint;

    @JsonProperty("apiKey")
    private String apiKey;

    @JsonProperty("parameters")
    private Map<String, Object> parameters;

    @JsonProperty("capabilities")
    private ModelCapabilities capabilities;

    public Model() {
        // Default constructor for JSON deserialization
    }

    public Model(Metadata metadata, String provider, String modelName, String version, String endpoint,
                 String apiKey, Map<String, Object> parameters, ModelCapabilities capabilities) {
        this.metadata = metadata;
        this.provider = provider;
        this.modelName = modelName;
        this.version = version;
        this.endpoint = endpoint;
        this.apiKey = apiKey;
        this.parameters = parameters;
        this.capabilities = capabilities;
    }

    // Getters
    public Metadata getMetadata() {
        return metadata;
    }

    public String getProvider() {
        return provider;
    }

    public String getModelName() {
        return modelName;
    }

    public String getVersion() {
        return version;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public String getApiKey() {
        return apiKey;
    }

    public Map<String, Object> getParameters() {
        return parameters;
    }

    public ModelCapabilities getCapabilities() {
        return capabilities;
    }

    // Setters
    public void setMetadata(Metadata metadata) {
        this.metadata = metadata;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public void setParameters(Map<String, Object> parameters) {
        this.parameters = parameters;
    }

    public void setCapabilities(ModelCapabilities capabilities) {
        this.capabilities = capabilities;
    }
}