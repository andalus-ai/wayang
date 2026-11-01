package tech.kayys.wayang.schema.api.dto;

public class SchemaValidationRequest {
    private String schema;
    private Object data;

    public SchemaValidationRequest() {
    }

    public SchemaValidationRequest(String schema, Object data) {
        this.schema = schema;
        this.data = data;
    }

    public String getSchema() {
        return schema;
    }

    public void setSchema(String schema) {
        this.schema = schema;
    }

    public Object getData() {
        return data;
    }

    public void setData(Object data) {
        this.data = data;
    }
}