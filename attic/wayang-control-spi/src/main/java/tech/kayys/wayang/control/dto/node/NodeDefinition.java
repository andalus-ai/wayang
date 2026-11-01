package tech.kayys.wayang.control.dto.node;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Complete node definition with UI descriptor
 */
public class NodeDefinition {
    public String type;
    public String label;
    public String category;
    public String subCategory;
    public String description;
    public String icon;
    public String color;
    public boolean isAtomic;
    public boolean isComposite;
    public List<ConfigField> configFields = new ArrayList<>();
    public List<NodePort> ports = new ArrayList<>();
    public List<NodeComponent> components = new ArrayList<>(); // For composite nodes
    public UIDescriptor uiDescriptor;
    public Map<String, Object> metadata = new HashMap<>();
    public String version = "1.0.0";
    public String author;
    public List<String> tags = new ArrayList<>();

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final NodeDefinition def = new NodeDefinition();

        public Builder type(String type) {
            def.type = type;
            return this;
        }

        public Builder label(String label) {
            def.label = label;
            return this;
        }

        public Builder category(String category) {
            def.category = category;
            return this;
        }

        public Builder subCategory(String subCategory) {
            def.subCategory = subCategory;
            return this;
        }

        public Builder description(String description) {
            def.description = description;
            return this;
        }

        public Builder icon(String icon) {
            def.icon = icon;
            return this;
        }

        public Builder color(String color) {
            def.color = color;
            return this;
        }

        public Builder isAtomic(boolean isAtomic) {
            def.isAtomic = isAtomic;
            return this;
        }

        public Builder isComposite(boolean isComposite) {
            def.isComposite = isComposite;
            return this;
        }

        public Builder addConfigField(ConfigField field) {
            def.configFields.add(field);
            return this;
        }

        public Builder addPort(NodePort port) {
            def.ports.add(port);
            return this;
        }

        public Builder addComponent(String id, String nodeType, Map<String, Object> config) {
            def.components.add(new NodeComponent(id, nodeType, config));
            return this;
        }

        public Builder uiDescriptor(UIDescriptor ui) {
            def.uiDescriptor = ui;
            return this;
        }

        public NodeDefinition build() {
            if (def.uiDescriptor == null) {
                def.uiDescriptor = UIDescriptor.defaultDescriptor();
            }
            return def;
        }
    }
}
