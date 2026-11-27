package tech.kayys.wayang.adapter.aqli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

final class AqliJson {
    private static final ObjectMapper JSON = new ObjectMapper();

    private AqliJson() {
    }

    static JsonNode parseObject(String value) {
        try {
            JsonNode node = JSON.readTree(value);
            if (node == null || !node.isObject()) {
                throw new IllegalStateException("expected one JSON object");
            }
            return node;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("invalid JSON object", e);
        }
    }
}
