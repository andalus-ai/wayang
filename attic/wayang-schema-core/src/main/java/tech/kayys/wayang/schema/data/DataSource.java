package tech.kayys.wayang.schema.data;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/**
 * Represents a data source configuration.
 */
public class DataSource {
    @JsonProperty("type")
    private String type;

    @JsonProperty("connectionString")
    private String connectionString;

    @JsonProperty("tableName")
    private String tableName;

    @JsonProperty("query")
    private String query;

    @JsonProperty("credentials")
    private Map<String, String> credentials;

    @JsonProperty("options")
    private Map<String, Object> options;

    public DataSource() {
        // Default constructor for JSON deserialization
    }

    public DataSource(String type, String connectionString, String tableName, String query,
                     Map<String, String> credentials, Map<String, Object> options) {
        this.type = type;
        this.connectionString = connectionString;
        this.tableName = tableName;
        this.query = query;
        this.credentials = credentials;
        this.options = options;
    }

    // Getters
    public String getType() {
        return type;
    }

    public String getConnectionString() {
        return connectionString;
    }

    public String getTableName() {
        return tableName;
    }

    public String getQuery() {
        return query;
    }

    public Map<String, String> getCredentials() {
        return credentials;
    }

    public Map<String, Object> getOptions() {
        return options;
    }

    // Setters
    public void setType(String type) {
        this.type = type;
    }

    public void setConnectionString(String connectionString) {
        this.connectionString = connectionString;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public void setQuery(String query) {
        this.query = query;
    }

    public void setCredentials(Map<String, String> credentials) {
        this.credentials = credentials;
    }

    public void setOptions(Map<String, Object> options) {
        this.options = options;
    }
}