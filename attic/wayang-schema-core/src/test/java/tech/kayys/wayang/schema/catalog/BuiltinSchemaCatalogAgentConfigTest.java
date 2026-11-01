package tech.kayys.wayang.schema.catalog;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuiltinSchemaCatalogAgentConfigTest {

    @Test
    void shouldExposeAgentConfigSchemaWithProviderModeAndSecrets() {
        String schema = BuiltinSchemaCatalog.get(BuiltinSchemaCatalog.AGENT_CONFIG);
        assertNotNull(schema);

        assertTrue(schema.contains("providerMode"));
        assertTrue(schema.contains("\"auto\""));
        assertTrue(schema.contains("\"local\""));
        assertTrue(schema.contains("\"cloud\""));

        assertTrue(schema.contains("localProvider"));
        assertTrue(schema.contains("cloudProvider"));
        assertTrue(schema.contains("providerId"));
        assertTrue(schema.contains("model"));

        assertTrue(schema.contains("credentialRefs"));
        assertTrue(schema.contains("path"));
        assertTrue(schema.contains("version"));
        assertTrue(schema.contains("backend"));

        assertTrue(schema.contains("vault"));
        assertTrue(schema.contains("tenantId"));
        assertTrue(schema.contains("pathPrefix"));
    }
}
