package tech.kayys.wayang.control.dto.node;

import java.util.List;
import java.util.Map;

/**
 * Configuration field definition
 */
public class ConfigField {
    public String name;
    public String label;
    public FieldType type;
    public boolean required;
    public Object defaultValue;
    public String description;
    public String placeholder;
    public Map<String, Object> validation;
    public List<String> options; // For select/multiselect
    public Map<String, Object> uiProps;

    public static Builder builder() {
        return new Builder();
    }

    // Convenience factory methods
    public static ConfigField text(String name, String label, boolean required) {
        return text(name, label, required, null);
    }

    public static ConfigField text(String name, String label, boolean required, Object defaultValue) {
        return builder()
                .name(name)
                .label(label)
                .type(FieldType.TEXT)
                .required(required)
                .defaultValue(defaultValue)
                .build();
    }

    public static ConfigField number(String name, String label, boolean required, Object defaultValue) {
        return builder()
                .name(name)
                .label(label)
                .type(FieldType.NUMBER)
                .required(required)
                .defaultValue(defaultValue)
                .build();
    }

    public static ConfigField password(String name, String label, boolean required) {
        return builder()
                .name(name)
                .label(label)
                .type(FieldType.PASSWORD)
                .required(required)
                .build();
    }

    public static ConfigField select(String name, String label, boolean required, List<String> options,
            String defaultValue) {
        return builder()
                .name(name)
                .label(label)
                .type(FieldType.SELECT)
                .required(required)
                .options(options)
                .defaultValue(defaultValue)
                .build();
    }

    public static ConfigField multiSelect(String name, String label, boolean required, List<String> options) {
        return builder()
                .name(name)
                .label(label)
                .type(FieldType.MULTI_SELECT)
                .required(required)
                .options(options)
                .build();
    }

    public static ConfigField toggle(String name, String label, boolean required, boolean defaultValue) {
        return builder()
                .name(name)
                .label(label)
                .type(FieldType.TOGGLE)
                .required(required)
                .defaultValue(defaultValue)
                .build();
    }

    public static ConfigField slider(String name, String label, boolean required, double defaultValue, double min,
            double max, double step) {
        return builder()
                .name(name)
                .label(label)
                .type(FieldType.SLIDER)
                .required(required)
                .defaultValue(defaultValue)
                .validation(Map.of("min", min, "max", max, "step", step))
                .build();
    }

    public static ConfigField code(String name, String label, boolean required, String language) {
        return builder()
                .name(name)
                .label(label)
                .type(FieldType.CODE)
                .required(required)
                .uiProps(Map.of("language", language))
                .build();
    }

    public static class Builder {
        private final ConfigField field = new ConfigField();

        public Builder name(String name) {
            field.name = name;
            return this;
        }

        public Builder label(String label) {
            field.label = label;
            return this;
        }

        public Builder type(FieldType type) {
            field.type = type;
            return this;
        }

        public Builder required(boolean required) {
            field.required = required;
            return this;
        }

        public Builder defaultValue(Object defaultValue) {
            field.defaultValue = defaultValue;
            return this;
        }

        public Builder description(String description) {
            field.description = description;
            return this;
        }

        public Builder placeholder(String placeholder) {
            field.placeholder = placeholder;
            return this;
        }

        public Builder validation(Map<String, Object> validation) {
            field.validation = validation;
            return this;
        }

        public Builder options(List<String> options) {
            field.options = options;
            return this;
        }

        public Builder uiProps(Map<String, Object> uiProps) {
            field.uiProps = uiProps;
            return this;
        }

        public ConfigField build() {
            return field;
        }
    }
}