package tech.kayys.wayang.adapter.aqli;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

import tech.kayys.wayang.rag.QueryMentionModel;
import tech.kayys.wayang.rag.QueryMentionPrediction;

/** AQLI chat-completion adapter for provider-neutral query mention extraction. */
public final class AqliQueryMentionModel implements QueryMentionModel {
    private static final String SYSTEM = """
            Extract named entity mentions from the user's query.
            Return exactly one JSON object and no markdown or prose:
            {"mentions":[{"entityType":"Organization","surfaceText":"Acme Corp","startOffset":8,"endOffset":17,"confidence":0.95}]}
            Offsets are zero-based Java UTF-16 character offsets into the exact query; endOffset is exclusive.
            surfaceText must equal the exact substring at those offsets. Do not invent entities or offsets.
            confidence must be a number from 0 to 1. Return an empty mentions array when none are present.
            """;

    private final AqliInferenceClient client;
    private final String model;
    private final int maxTokens;

    public AqliQueryMentionModel(AqliInferenceClient client, String model) {
        this(client, model, 1024);
    }

    public AqliQueryMentionModel(AqliInferenceClient client, String model, int maxTokens) {
        if (client == null || model == null || model.isBlank()) {
            throw new IllegalArgumentException("AQLI client and chat model are required");
        }
        if (maxTokens < 64 || maxTokens > 4096) {
            throw new IllegalArgumentException("mention extraction maxTokens must be between 64 and 4096");
        }
        this.client = client;
        this.model = model;
        this.maxTokens = maxTokens;
    }

    @Override
    public List<QueryMentionPrediction> extract(String query, int maxMentions) {
        if (query == null || query.isBlank() || query.length() > 2_000
                || maxMentions < 1 || maxMentions > 100) {
            throw new IllegalArgumentException("query must contain 1..2000 characters and maxMentions must be 1..100");
        }
        String content = client.chatCompletion(model, SYSTEM,
                "Extract up to " + maxMentions + " mentions from this exact query:\n" + query, maxTokens);
        try {
            JsonNode mentions = AqliJson.parseObject(content).path("mentions");
            if (!mentions.isArray() || mentions.size() > maxMentions) {
                throw new IllegalStateException("AQLI mention response is missing a bounded mentions array");
            }
            List<QueryMentionPrediction> predictions = new ArrayList<>(mentions.size());
            for (JsonNode mention : mentions) {
                if (!mention.isObject() || !mention.path("entityType").isTextual()
                        || !mention.path("surfaceText").isTextual()
                        || !mention.path("startOffset").canConvertToInt()
                        || !mention.path("endOffset").canConvertToInt()
                        || !mention.path("confidence").isNumber()) {
                    throw new IllegalStateException("AQLI returned a malformed mention prediction");
                }
                predictions.add(new QueryMentionPrediction(
                        mention.path("entityType").textValue(),
                        mention.path("surfaceText").textValue(),
                        mention.path("startOffset").intValue(),
                        mention.path("endOffset").intValue(),
                        mention.path("confidence").doubleValue()));
            }
            return List.copyOf(predictions);
        } catch (RuntimeException malformed) {
            throw new IllegalStateException("AQLI returned invalid structured mention output", malformed);
        }
    }
}
