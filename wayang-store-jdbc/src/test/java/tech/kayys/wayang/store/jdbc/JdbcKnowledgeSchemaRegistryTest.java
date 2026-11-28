package tech.kayys.wayang.store.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import tech.kayys.wayang.rag.KnowledgeSchema;
import tech.kayys.wayang.rag.RagException;
import tech.kayys.wayang.rag.RelationshipTypeDefinition;

/** PostgreSQL integration coverage; enabled when WAYANG_TEST_JDBC_URL is configured. */
class JdbcKnowledgeSchemaRegistryTest {

    @Test
    void persistsExactVersionsAndIsolatesTenants() {
        String url = System.getenv("WAYANG_TEST_JDBC_URL");
        if (url == null || url.isBlank()) return;

        var dataSource = new JdbcWaitRegistryIT.DriverManagerDataSource(url);
        JdbcSchema.apply(dataSource);
        var registry = new JdbcKnowledgeSchemaRegistry(dataSource);
        String tenantA = "schema-test-a-" + UUID.randomUUID();
        String tenantB = "schema-test-b-" + UUID.randomUUID();

        var tenantASchema = schema("1.0", Set.of("Organization"));
        var tenantBSchema = schema("1.0", Set.of("Person"));
        registry.register(tenantA, tenantASchema);
        registry.register(tenantA, tenantASchema); // identical registration is idempotent
        registry.register(tenantB, tenantBSchema); // same schema key may differ in another tenant

        assertEquals(tenantASchema, registry.find(tenantA, "acme.graph", "1.0").orElseThrow());
        assertEquals(tenantBSchema, registry.find(tenantB, "acme.graph", "1.0").orElseThrow());
        assertTrue(registry.find(tenantA, "acme.graph", "2.0").isEmpty());
        assertEquals(1, registry.versions(tenantA, "acme.graph").size());
    }

    @Test
    void rejectsMutationOfAnExistingTenantSchemaVersion() {
        String url = System.getenv("WAYANG_TEST_JDBC_URL");
        if (url == null || url.isBlank()) return;

        var dataSource = new JdbcWaitRegistryIT.DriverManagerDataSource(url);
        JdbcSchema.apply(dataSource);
        var registry = new JdbcKnowledgeSchemaRegistry(dataSource);
        String tenant = "schema-test-" + UUID.randomUUID();
        registry.register(tenant, schema("1.0", Set.of("Organization")));

        assertThrows(RagException.class,
                () -> registry.register(tenant, schema("1.0", Set.of("Organization", "Person"))));
    }

    @Test
    void listsVersionsInStableLexicalOrder() {
        String url = System.getenv("WAYANG_TEST_JDBC_URL");
        if (url == null || url.isBlank()) return;

        var dataSource = new JdbcWaitRegistryIT.DriverManagerDataSource(url);
        JdbcSchema.apply(dataSource);
        var registry = new JdbcKnowledgeSchemaRegistry(dataSource);
        String tenant = "schema-test-" + UUID.randomUUID();
        registry.register(tenant, schema("2.0", Set.of("Organization")));
        registry.register(tenant, schema("1.0", Set.of("Organization")));

        assertEquals(java.util.List.of("1.0", "2.0"),
                registry.versions(tenant, "acme.graph").stream().map(KnowledgeSchema::version).toList());
    }

    private static KnowledgeSchema schema(String version, Set<String> entityTypes) {
        return new KnowledgeSchema("acme.graph", version, entityTypes,
                Map.of("RELATED_TO", new RelationshipTypeDefinition(
                        "RELATED_TO", entityTypes, entityTypes)));
    }
}
