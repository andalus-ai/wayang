package tech.kayys.wayang.control.dto.node;

import java.util.Map;

/**
 * 
 * UI descriptor for visual rendering
 */
public class UIDescriptor {
    public int width = 200;
    public int height = 100;
    public String icon;
    public String color;
    public UIStyle style = UIStyle.ROUNDED;
    public String badge;
    public boolean showComponents = false;
    public Map<String, Object> customStyles;

    public static Builder builder() {
        return new Builder();
    }

    public static UIDescriptor defaultDescriptor() {
        return builder().build();
    }

    public static class Builder {
        private final UIDescriptor ui = new UIDescriptor();

        public UIDescriptor build() {
            return ui;
        }

        public Builder width(int width) {
            ui.width = width;
            return this;
        }

        public Builder height(int height) {
            ui.height = height;
            return this;
        }

        public Builder icon(String icon) {
            ui.icon = icon;
            return this;
        }

        public Builder color(String color) {
            ui.color = color;
            return this;
        }

        public Builder style(UIStyle style) {
            ui.style = style;
            return this;
        }

        public Builder badge(String badge) {
            ui.badge = badge;
            return this;
        }

        public Builder showComponents(boolean showComponents) {
            ui.showComponents = showComponents;
            return this;
        }

        public Builder customStyles(Map<String, Object> customStyles) {
            ui.customStyles = customStyles;
            return this;
        }
    }
}