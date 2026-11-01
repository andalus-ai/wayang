package tech.kayys.wayang.schema.vector;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

/**
 * Configuration for Vector Search operations.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class VectorSearchConfig {
    private int topK = 10;
    private Map<String, Object> filters;

    public int getTopK() {
        return topK;
    }

    public void setTopK(int topK) {
        this.topK = topK;
    }

    public Map<String, Object> getFilters() {
        return filters;
    }

    public void setFilters(Map<String, Object> filters) {
        this.filters = filters;
    }
}
