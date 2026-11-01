package tech.kayys.wayang.api;

import java.util.List;

/** Result of validating a definition document; what the Studio shows as problem markers. */
public record ValidationReport(boolean valid, List<Item> items) {
    public record Item(String severity, String elementId, String message) {
    }

    public ValidationReport {
        items = List.copyOf(items);
    }

    public static ValidationReport of(List<Item> items) {
        return new ValidationReport(items.stream().noneMatch(i -> i.severity().equals("ERROR")), items);
    }
}
