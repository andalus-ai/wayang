package tech.kayys.wayang.rag;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic relationship extractor that binds configured regex capture groups to exactly
 * one matching entity mention. Ambiguous endpoint matches are skipped, never guessed.
 */
public final class RegexRelationshipExtractor implements RelationshipExtractor {

    public static final int DEFAULT_MAX_CANDIDATES = 500;

    public record Rule(String predicate, Pattern pattern, int fromGroup, int toGroup, double confidence) {
        public Rule {
            if (predicate == null || predicate.isBlank() || predicate.length() > 120) {
                throw new RagException("relationship rule needs a predicate of 1..120 characters");
            }
            if (pattern == null) throw new RagException("relationship rule pattern is required");
            int groups = pattern.matcher("").groupCount();
            if (fromGroup < 1 || fromGroup > groups || toGroup < 1 || toGroup > groups || fromGroup == toGroup) {
                throw new RagException("relationship rule endpoint groups must be distinct capture groups");
            }
            if (!Double.isFinite(confidence) || confidence < 0 || confidence > 1) {
                throw new RagException("relationship rule confidence must be between 0 and 1");
            }
        }
    }

    private final List<Rule> rules;
    private final int maxCandidates;

    public RegexRelationshipExtractor(List<Rule> rules) {
        this(rules, DEFAULT_MAX_CANDIDATES);
    }

    public RegexRelationshipExtractor(List<Rule> rules, int maxCandidates) {
        if (rules == null || rules.isEmpty()) throw new RagException("at least one relationship extraction rule is required");
        if (maxCandidates < 1 || maxCandidates > 10_000) throw new RagException("relationship candidate limit must be 1..10000");
        this.rules = List.copyOf(rules);
        this.maxCandidates = maxCandidates;
    }

    @Override
    public List<RelationshipCandidate> extract(KnowledgeExtractionInput input, List<EntityCandidate> entities) {
        if (input == null) throw new RagException("knowledge extraction input is required");
        if (entities == null || entities.isEmpty()) return List.of();
        List<EntityCandidate> safeEntities = List.copyOf(entities);
        List<RelationshipCandidate> result = new ArrayList<>();
        for (Rule rule : rules) {
            Matcher matcher = rule.pattern().matcher(input.chunk().text());
            while (matcher.find()) {
                String fromText = RegexEntityExtractor.normalize(matcher.group(rule.fromGroup()));
                String toText = RegexEntityExtractor.normalize(matcher.group(rule.toGroup()));
                EntityCandidate from = uniqueMatch(safeEntities, fromText);
                EntityCandidate to = uniqueMatch(safeEntities, toText);
                if (from == null || to == null || from.candidateId().equals(to.candidateId())) continue;
                int start = matcher.start();
                int end = matcher.end();
                String id = candidateId(input, rule.predicate(), from.candidateId(), to.candidateId(), start, end);
                result.add(new RelationshipCandidate(id, from.candidateId(), rule.predicate(), to.candidateId(),
                        start, end, rule.confidence()));
                if (result.size() >= maxCandidates) return List.copyOf(result);
            }
        }
        return List.copyOf(result);
    }

    private static EntityCandidate uniqueMatch(List<EntityCandidate> entities, String normalizedText) {
        EntityCandidate found = null;
        for (EntityCandidate entity : entities) {
            if (!entity.normalizedName().equals(normalizedText)) continue;
            if (found != null) return null;
            found = entity;
        }
        return found;
    }

    private static String candidateId(KnowledgeExtractionInput input, String predicate,
            String from, String to, int start, int end) {
        String identity = input.tenantId() + "\n" + input.collectionId() + "\n"
                + input.document().id() + "\n" + input.sourceVersion() + "\n" + input.chunk().id()
                + "\n" + predicate + "\n" + from + "\n" + to + "\n" + start + "\n" + end;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8));
            return "relation-" + HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new RagException("SHA-256 is unavailable for relationship identity", e);
        }
    }
}
