package tech.kayys.wayang.adapter.aqli;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

import tech.kayys.wayang.rag.EntityCandidate;
import tech.kayys.wayang.rag.EntityMatchPrediction;
import tech.kayys.wayang.rag.EntityResolutionModel;
import tech.kayys.wayang.rag.EntityResolutionRequest;
import tech.kayys.wayang.rag.KnowledgeEntity;

/**
 * Ranks only canonical candidates already supplied for the request's tenant and collection.
 * Tenant and collection identifiers are used locally for filtering and are never sent to AQLI.
 */
public final class AqliEntityResolutionModel implements EntityResolutionModel {
    private static final String SYSTEM = """
            Resolve each mention only against the candidate entities listed for that mention.
            Return exactly one JSON object and no markdown or prose:
            {"matches":[{"candidateId":"mention-id","canonicalEntityId":"entity-id","confidence":0.96,"runnerUpConfidence":0.12}]}
            Use canonicalEntityId:null when no supplied entity is a defensible match.
            Never invent candidate IDs or canonical entity IDs. Scores must be in [0,1] and
            runnerUpConfidence must be no greater than confidence. Return one match per mention.
            """;
    private static final int MAX_MENTIONS = 100;
    private static final int MAX_ENTITIES_PER_MENTION = 100;
    private static final int MAX_TOTAL_ENTITIES = 2_000;
    private static final int MAX_ALIASES = 20;

    private final AqliInferenceClient client;
    private final String model;
    private final int maxTokens;

    public AqliEntityResolutionModel(AqliInferenceClient client, String model) {
        this(client, model, 2048);
    }

    public AqliEntityResolutionModel(AqliInferenceClient client, String model, int maxTokens) {
        if (client == null || model == null || model.isBlank()) {
            throw new IllegalArgumentException("AQLI client and chat model are required");
        }
        if (maxTokens < 64 || maxTokens > 4096) {
            throw new IllegalArgumentException("entity resolution maxTokens must be between 64 and 4096");
        }
        this.client = client;
        this.model = model;
        this.maxTokens = maxTokens;
    }

    @Override
    public List<EntityMatchPrediction> rank(EntityResolutionRequest request) {
        if (request == null || request.candidates().isEmpty()
                || request.candidates().size() > MAX_MENTIONS) {
            throw new IllegalArgumentException("entity resolution requires 1..100 mentions");
        }
        List<Map<String, Object>> mentions = new ArrayList<>(request.candidates().size());
        int totalEntities = 0;
        for (EntityCandidate mention : request.candidates()) {
            List<Map<String, Object>> scoped = new ArrayList<>();
            for (KnowledgeEntity entity : request.existingEntities()) {
                if (entity.tenantId().equals(request.tenantId())
                        && entity.collectionId().equals(request.collectionId())
                        && entity.type().equals(mention.entityType())
                        && scoped.size() < MAX_ENTITIES_PER_MENTION
                        && totalEntities < MAX_TOTAL_ENTITIES) {
                    Map<String, Object> candidate = new LinkedHashMap<>();
                    candidate.put("id", entity.id());
                    candidate.put("type", entity.type());
                    candidate.put("canonicalName", entity.canonicalName());
                    candidate.put("aliases", entity.aliases().stream().filter(alias -> alias.length() <= 500)
                            .limit(MAX_ALIASES).toList());
                    scoped.add(candidate);
                    totalEntities++;
                }
            }
            mentions.add(Map.of(
                    "candidateId", mention.candidateId(),
                    "entityType", mention.entityType(),
                    "surfaceText", mention.surfaceText(),
                    "normalizedName", mention.normalizedName(),
                    "existingEntities", scoped));
        }
        String input;
        try {
            input = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of("mentions", mentions));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("could not encode AQLI entity resolution input", e);
        }
        String content = client.chatCompletion(model, SYSTEM,
                "Resolve these mentions using only their supplied candidate lists:\n" + input, maxTokens);
        try {
            JsonNode matches = AqliJson.parseObject(content).path("matches");
            if (!matches.isArray() || matches.size() != request.candidates().size()) {
                throw new IllegalStateException("AQLI entity resolution response count did not match mentions");
            }
            List<EntityMatchPrediction> predictions = new ArrayList<>(matches.size());
            for (JsonNode match : matches) {
                if (!match.isObject() || !match.path("candidateId").isTextual()
                        || !(match.path("canonicalEntityId").isTextual()
                            || match.path("canonicalEntityId").isNull())
                        || !match.path("confidence").isNumber()
                        || !match.path("runnerUpConfidence").isNumber()) {
                    throw new IllegalStateException("AQLI returned a malformed entity match");
                }
                predictions.add(new EntityMatchPrediction(
                        match.path("candidateId").textValue(),
                        match.path("canonicalEntityId").isNull() ? null
                                : match.path("canonicalEntityId").textValue(),
                        match.path("confidence").doubleValue(),
                        match.path("runnerUpConfidence").doubleValue()));
            }
            return List.copyOf(predictions);
        } catch (RuntimeException malformed) {
            throw new IllegalStateException("AQLI returned invalid structured entity resolution output", malformed);
        }
    }
}
