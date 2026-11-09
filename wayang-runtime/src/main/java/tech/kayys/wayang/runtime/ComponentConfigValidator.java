package tech.kayys.wayang.runtime;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Deterministic validator for the JSON Schema subset used by component contracts.
 * It intentionally implements only documented keywords; this is not a full Draft 2020-12 engine.
 */
public final class ComponentConfigValidator {
    private ComponentConfigValidator() {}
    public record Violation(String path, String message) {}

    public static List<Violation> validate(ComponentDescriptor descriptor, Map<String, ?> config) {
        Objects.requireNonNull(descriptor, "descriptor");
        return validate(descriptor.configSchema(), config == null ? Map.of() : config);
    }

    public static List<Violation> validate(Map<String, Object> schema, Map<String, ?> value) {
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(value, "value");
        List<Violation> out = new ArrayList<>();
        validateObject(schema, value, "$", out);
        return List.copyOf(out);
    }

    private static void validateObject(Map<String, Object> schema, Map<String, ?> value, String path, List<Violation> out) {
        if (!"object".equals(schema.get("type"))) {
            out.add(new Violation(path, "schema root must declare type object"));
            return;
        }
        Map<String, Object> properties = map(schema.get("properties"));
        for (Object required : list(schema.get("required"))) {
            String key = String.valueOf(required);
            if (!value.containsKey(key) || value.get(key) == null) out.add(new Violation(path + "." + key, "is required"));
        }
        boolean additional = !Boolean.FALSE.equals(schema.get("additionalProperties"));
        for (Map.Entry<String, ?> entry : value.entrySet()) {
            Object propertySchema = properties.get(entry.getKey());
            if (propertySchema == null) {
                if (!additional) out.add(new Violation(path + "." + entry.getKey(), "is not allowed"));
            } else validateValue(map(propertySchema), entry.getValue(), path + "." + entry.getKey(), out);
        }
    }

    private static void validateValue(Map<String, Object> schema, Object value, String path, List<Violation> out) {
        if (value == null) return;
        Object type = schema.get("type");
        boolean valid = type instanceof List<?> types
                ? types.stream().anyMatch(t -> matches(String.valueOf(t), value))
                : matches(type == null ? "" : String.valueOf(type), value);
        if (!valid) {
            out.add(new Violation(path, "must be of type " + type));
            return;
        }
        if (value instanceof String s) {
            if (schema.get("minLength") instanceof Number n && s.length() < n.intValue())
                out.add(new Violation(path, "must contain at least " + n + " characters"));
            if (schema.get("maxLength") instanceof Number n && s.length() > n.intValue())
                out.add(new Violation(path, "must contain at most " + n + " characters"));
            if (schema.get("pattern") instanceof String p && !Pattern.compile(p).matcher(s).matches())
                out.add(new Violation(path, "does not match required pattern"));
        }
        if (value instanceof Number n) {
            BigDecimal actual = new BigDecimal(n.toString());
            if (schema.get("minimum") instanceof Number min && actual.compareTo(new BigDecimal(min.toString())) < 0)
                out.add(new Violation(path, "must be >= " + min));
            if (schema.get("maximum") instanceof Number max && actual.compareTo(new BigDecimal(max.toString())) > 0)
                out.add(new Violation(path, "must be <= " + max));
        }
        if (schema.get("enum") instanceof List<?> choices && !choices.contains(value))
            out.add(new Violation(path, "must be one of " + choices));
        if (value instanceof Map<?, ?> nested) {
            Map<String, Object> nestedValues = new java.util.LinkedHashMap<>();
            nested.forEach((k, v) -> nestedValues.put(String.valueOf(k), v));
            validateObject(schema, nestedValues, path, out);
        }
        if (value instanceof List<?> items && schema.get("items") instanceof Map<?, ?> itemSchema) {
            Map<String, Object> item = new java.util.LinkedHashMap<>();
            itemSchema.forEach((k, v) -> item.put(String.valueOf(k), v));
            for (int i = 0; i < items.size(); i++) validateValue(item, items.get(i), path + "[" + i + "]", out);
        }
    }

    private static boolean matches(String type, Object value) {
        return switch (type) {
            case "string" -> value instanceof String;
            case "integer" -> value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long
                    || value instanceof BigDecimal b && b.stripTrailingZeros().scale() <= 0;
            case "number" -> value instanceof Number;
            case "boolean" -> value instanceof Boolean;
            case "object" -> value instanceof Map<?, ?>;
            case "array" -> value instanceof List<?>;
            default -> false;
        };
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }
    private static List<?> list(Object value) { return value instanceof List<?> l ? l : List.of(); }
}
