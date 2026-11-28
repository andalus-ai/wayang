package tech.kayys.wayang.rag;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class KnowledgeSchemaRegistryTest {

    @Test
    void registersImmutableVersionsAndAllowsIdempotentRegistration() {
        var registry = new InMemoryKnowledgeSchemaRegistry();
        var v1 = schema("1.0", Set.of("Organization"), Set.of("Organization"));
        registry.register(v1);
        registry.register(v1);

        assertEquals(v1, registry.find("acme.graph", "1.0").orElseThrow());
        assertEquals(1, registry.versions("acme.graph").size());
    }

    @Test
    void rejectsMutationOfAnExistingVersion() {
        var registry = new InMemoryKnowledgeSchemaRegistry();
        registry.register(schema("1.0", Set.of("Organization"), Set.of("Organization")));

        assertThrows(RagException.class, () -> registry.register(
                schema("1.0", Set.of("Organization", "Person"), Set.of("Organization"))));
    }

    @Test
    void enforcesCapacity() {
        var registry = new InMemoryKnowledgeSchemaRegistry(1);
        registry.register(schema("1.0", Set.of("Organization"), Set.of("Organization")));

        assertThrows(RagException.class, () -> registry.register(
                schema("1.1", Set.of("Organization"), Set.of("Organization"))));
    }

    @Test
    void additiveSchemaEvolutionIsCompatible() {
        var previous = schema("1.0", Set.of("Organization"), Set.of("Organization"));
        var next = schema("1.1", Set.of("Organization", "Person"), Set.of("Organization", "Person"));

        var result = KnowledgeSchemaCompatibility.check(previous, next);

        assertTrue(result.compatible());
        assertTrue(result.breakingChanges().isEmpty());
    }

    @Test
    void removingTypesOrNarrowingRelationshipEndpointsIsBreaking() {
        var previous = schema("1.0", Set.of("Organization", "Person"),
                Set.of("Organization", "Person"));
        var next = schema("2.0", Set.of("Organization"),
                Set.of("Organization"));

        var result = KnowledgeSchemaCompatibility.check(previous, next);

        assertFalse(result.compatible());
        assertTrue(result.breakingChanges().stream().anyMatch(v -> v.contains("entity type removed")));
        assertTrue(result.breakingChanges().stream().anyMatch(v -> v.contains("source types narrowed")));
        assertTrue(result.breakingChanges().stream().anyMatch(v -> v.contains("target types narrowed")));
    }

    private static KnowledgeSchema schema(
            String version, Set<String> entities, Set<String> endpoints) {
        return new KnowledgeSchema("acme.graph", version, entities,
                Map.of("OWNS", new RelationshipTypeDefinition("OWNS", endpoints, endpoints)));
    }
}
