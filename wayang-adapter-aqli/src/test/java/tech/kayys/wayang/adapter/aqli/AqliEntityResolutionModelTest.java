package tech.kayys.wayang.adapter.aqli;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tech.kayys.wayang.rag.EntityCandidate;
import tech.kayys.wayang.rag.EntityResolutionRequest;
import tech.kayys.wayang.rag.KnowledgeEntity;

class AqliEntityResolutionModelTest {
    @Test
    void sendsOnlyEntitiesInTheRequestedTenantAndCollection() {
        KnowledgeEntity allowed = entity("tenant-a", "docs", "acme", "Acme Corporation");
        KnowledgeEntity foreignTenant = entity("tenant-b", "docs", "secret", "Secret Corporation");
        KnowledgeEntity foreignCollection = entity("tenant-a", "other", "other", "Other Corporation");
        EntityCandidate mention = new EntityCandidate("mention-1", "Organization",
                "Acme Corp", "acme corp", 0, 9, 0.9);

        AqliInferenceClient client = new AqliInferenceClient(URI.create("http://localhost:9131"),
                "key", Duration.ofSeconds(3), 100_000,
                (uri, headers, body, timeout) -> {
                    assertTrue(body.contains("Acme Corporation"));
                    assertFalse(body.contains("Secret Corporation"));
                    assertFalse(body.contains("Other Corporation"));
                    assertFalse(body.contains("tenant-a"));
                    return new AqliInferenceClient.Response(200,
                            """
                            {"choices":[{"message":{"content":"{\\"matches\\":[{\\"candidateId\\":\\"mention-1\\",\\"canonicalEntityId\\":\\"acme\\",\\"confidence\\":0.96,\\"runnerUpConfidence\\":0.1}]}"} }]}
                            """);
                });
        AqliEntityResolutionModel model = new AqliEntityResolutionModel(client, "local-model");

        var result = model.rank(new EntityResolutionRequest("tenant-a", "docs",
                List.of(mention), List.of(allowed, foreignTenant, foreignCollection)));

        assertEquals(1, result.size());
        assertEquals("acme", result.get(0).canonicalEntityId());
    }

    private static KnowledgeEntity entity(String tenant, String collection, String id, String name) {
        return new KnowledgeEntity(tenant, collection, id, "Organization", name,
                List.of(), Map.of(), List.of());
    }
}
