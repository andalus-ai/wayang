package tech.kayys.wayang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ComponentConfigValidatorTest {
    @Test
    void validatesRequiredTypesBoundsAndUnknownKeys() {
        var descriptor = BuiltInComponents.all().stream()
                .filter(c -> c.id().equals("wayang.service.connector")).findFirst().orElseThrow();
        var violations = ComponentConfigValidator.validate(descriptor, Map.of("timeoutMs", 20, "extra", true));
        assertTrue(violations.stream().anyMatch(v -> v.path().equals("$.connector") && v.message().equals("is required")));
        assertTrue(violations.stream().anyMatch(v -> v.path().equals("$.timeoutMs")));
        assertTrue(violations.stream().anyMatch(v -> v.path().equals("$.extra")));
    }

    @Test
    void acceptsValidConfigurationAndNestedArrays() {
        Map<String, Object> schema = Map.of("type", "object", "properties", Map.of(
                "enabled", Map.of("type", "boolean"),
                "targets", Map.of("type", "array", "items", Map.of("type", "string", "minLength", 2))),
                "required", List.of("enabled"), "additionalProperties", false);
        assertTrue(ComponentConfigValidator.validate(schema, Map.of("enabled", true, "targets", List.of("api", "db"))).isEmpty());
    }

    @Test
    void descriptorDeepCopiesNestedSchema() {
        var properties = new java.util.LinkedHashMap<String, Object>();
        properties.put("name", new java.util.LinkedHashMap<>(Map.of("type", "string")));
        var schema = new java.util.LinkedHashMap<String, Object>();
        schema.put("type", "object");
        schema.put("properties", properties);
        var descriptor = new ComponentDescriptor("example.component", "1.0.0", ComponentDescriptor.Kind.NODE,
                "example", "Example", java.util.Set.of(), List.of(), ComponentDescriptor.IsolationLevel.IN_PROCESS,
                java.util.Set.of(), "Example", "Test", null, schema, Map.of());
        properties.clear();
        assertTrue(((Map<?, ?>) descriptor.configSchema().get("properties")).containsKey("name"));
        assertThrows(UnsupportedOperationException.class,
                () -> ((Map<?, ?>) descriptor.configSchema().get("properties")).clear());
    }
}
