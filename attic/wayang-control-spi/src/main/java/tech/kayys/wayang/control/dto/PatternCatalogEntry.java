package tech.kayys.wayang.control.dto;

public record PatternCatalogEntry(
                String id,
                String name,
                String description,
                String category,
                EIPPatternType patternType,
                String documentation) {
}