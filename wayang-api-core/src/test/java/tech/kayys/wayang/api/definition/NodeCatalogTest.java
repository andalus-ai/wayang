package tech.kayys.wayang.api.definition;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tech.kayys.wayang.bpmn.ElementType;

class NodeCatalogTest {

    @Test
    void everyElementTypeHasAnEntryAndWaitsMatchTheEnum() {
        for (ElementType t : ElementType.values()) {
            var e = NodeCatalog.entry(t);
            assertEquals(t.name(), e.type());
            assertEquals(t.isWait(), e.waits(), t + " waits flag must agree with ElementType");
            assertEquals("object", e.configSchema().get("type"));
        }
        assertEquals(ElementType.values().length, NodeCatalog.entries().size());
    }

    @Test
    void validConfigsPass() {
        assertTrue(NodeCatalog.validateConfig(ElementType.SERVICE_TASK, Map.of("connector", "http", "retries", 3, "retryDelay", "PT2S",
                "retryBackoff", 1.5, "onError", "continue", "inputs", Map.of("a", "x + 1"))).isEmpty());
        assertTrue(NodeCatalog.validateConfig(ElementType.AGENT_TASK, Map.of("agent", "support-bot")).isEmpty());
        assertTrue(NodeCatalog.validateConfig(ElementType.AGENT_TASK, Map.of("agent", Map.of("id", "x"))).isEmpty());
        assertTrue(NodeCatalog.validateConfig(ElementType.TIMER_EVENT, Map.of("duration", "PT1H30M")).isEmpty());
        assertTrue(NodeCatalog.validateConfig(ElementType.START_EVENT, Map.of()).isEmpty());
    }

    @Test
    void httpConnectorConfigIsChecked() {
        assertTrue(NodeCatalog.validateConfig(ElementType.SERVICE_TASK, Map.of("connector", "http", "url", "https://x.example/a",
                "method", "POST", "headers", Map.of("Authorization", "Bearer ${secret:T}"), "timeoutMs", 5000)).isEmpty());
        assertFalse(NodeCatalog.validateConfig(ElementType.SERVICE_TASK, Map.of("connector", "http", "timeoutMs", 5)).isEmpty());
        assertFalse(NodeCatalog.validateConfig(ElementType.SERVICE_TASK, Map.of("connector", "http", "method", "TRACE")).isEmpty());
        assertFalse(NodeCatalog.validateConfig(ElementType.SERVICE_TASK, Map.of("connector", "http", "url", 5)).isEmpty());
    }

    @Test
    void invalidConfigsAreExplained() {
        assertTrue(NodeCatalog.validateConfig(ElementType.SERVICE_TASK, Map.of()).get(0).contains("'connector' is required"));
        assertFalse(NodeCatalog.validateConfig(ElementType.SERVICE_TASK, Map.of("connector", "c", "retries", 11)).isEmpty());
        assertFalse(NodeCatalog.validateConfig(ElementType.SERVICE_TASK, Map.of("connector", "c", "retries", 1.5)).isEmpty());
        assertFalse(NodeCatalog.validateConfig(ElementType.SERVICE_TASK, Map.of("connector", "c", "onError", "ignore")).isEmpty());
        assertFalse(NodeCatalog.validateConfig(ElementType.SERVICE_TASK, Map.of("connector", "c", "retryDelay", "5 seconds")).isEmpty());
        assertFalse(NodeCatalog.validateConfig(ElementType.SERVICE_TASK, Map.of("connector", "c", "typo", 1)).isEmpty());
        assertFalse(NodeCatalog.validateConfig(ElementType.BUSINESS_RULE_TASK, Map.of("model", 5)).isEmpty());
        assertFalse(NodeCatalog.validateConfig(ElementType.USER_TASK, Map.of("retries", 1)).isEmpty(), "human tasks take no retry policy");
        assertFalse(NodeCatalog.validateConfig(ElementType.EXCLUSIVE_GATEWAY, Map.of("x", 1)).isEmpty());
    }

    @Test
    void schemaLiteHandlesTheKeywordsItClaims() {
        Map<String, Object> s = Map.of("type", "object", "required", List.of("a"), "properties", Map.of(
                "a", Map.of("type", "string", "minLength", 2, "maxLength", 3, "pattern", "^[a-z]+$"),
                "n", Map.of("type", "number", "minimum", 1, "maximum", 5),
                "l", Map.of("type", "array", "minItems", 1, "maxItems", 2, "items", Map.of("type", "integer")),
                "e", Map.of("enum", List.of("x", 2)),
                "o", Map.of("oneOf", List.of(Map.of("type", "string"), Map.of("type", "number"))),
                "any", Map.of("anyOf", List.of(Map.of("type", "string"), Map.of("type", "boolean"))),
                "c", Map.of("const", "k")), "additionalProperties", false);
        assertTrue(JsonSchemaLite.validate(s, Map.of("a", "ab", "n", 3, "l", List.of(1, 2), "e", 2, "o", 4, "any", true, "c", "k")).isEmpty());
        assertEquals(1, JsonSchemaLite.validate(s, Map.of("a", "A1")).size());
        assertEquals(1, JsonSchemaLite.validate(s, Map.of("a", "ab", "n", 9)).size());
        assertEquals(3, JsonSchemaLite.validate(s, Map.of("a", "ab", "l", List.of("x", 2.5, 3))).size());
        assertFalse(JsonSchemaLite.validate(s, Map.of("a", "ab", "e", "nope")).isEmpty());
        assertFalse(JsonSchemaLite.validate(s, Map.of("a", "ab", "o", true)).isEmpty());
        assertFalse(JsonSchemaLite.validate(s, Map.of("a", "ab", "c", "z")).isEmpty());
        assertFalse(JsonSchemaLite.validate(s, "not an object").isEmpty());
        assertTrue(JsonSchemaLite.validate(s, Map.of("a", "ab", "l", List.of(1))).isEmpty());
    }
}
