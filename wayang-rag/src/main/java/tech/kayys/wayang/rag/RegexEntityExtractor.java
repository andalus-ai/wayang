package tech.kayys.wayang.rag;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic, dependency-free entity mention extractor.
 *
 * <p>Patterns are trusted application configuration, not end-user expressions. Keep patterns
 * simple and bounded; this implementation caps emitted candidates but cannot make a pathological
 * regular expression safe from excessive backtracking.
 */
public final class RegexEntityExtractor implements EntityExtractor {

    public static final int DEFAULT_MAX_CANDIDATES = 500;

    public record Rule(String entityType, Pattern pattern, int textGroup, double confidence) {
        public Rule {
            if (entityType == null || entityType.isBlank() || entityType.length() > 120) {
                throw new RagException("entity rule needs an entity type of 1..120 characters");
            }
            if (pattern == null) throw new RagException("entity rule pattern is required");
            if (textGroup < 0 || textGroup > pattern.matcher("").groupCount()) {
                throw new RagException("entity rule text group is outside the pattern groups");
            }
            if (!Double.isFinite(confidence) || confidence < 0 || confidence > 1) {
                throw new RagException("entity rule confidence must be between 0 and 1");
            }
        }
    }

    private final List<Rule> rules;
    private final int maxCandidates;

    public RegexEntityExtractor(List<Rule> rules) {
        this(rules, DEFAULT_MAX_CANDIDATES);
    }

    public RegexEntityExtractor(List<Rule> rules, int maxCandidates) {
        if (rules == null || rules.isEmpty()) throw new RagException("at least one entity extraction rule is required");
        if (maxCandidates < 1 || maxCandidates > 10_000) throw new RagException("entity candidate limit must be 1..10000");
        this.rules = List.copyOf(rules);
        this.maxCandidates = maxCandidates;
    }

    @Override
    public List<EntityCandidate> extract(KnowledgeExtractionInput input) {
        if (input == null) throw new RagException("knowledge extraction input is required");
        String text = input.chunk().text();
        List<EntityCandidate> result = new ArrayList<>();
        for (Rule rule : rules) {
            Matcher matcher = rule.pattern().matcher(text);
            while (matcher.find()) {
                String surface = matcher.group(rule.textGroup());
                int start = matcher.start(rule.textGroup());
                int end = matcher.end(rule.textGroup());
                if (surface == null || surface.isBlank() || start < 0 || end <= start) continue;
                String normalized = normalize(surface);
                String id = candidateId(input, rule.entityType(), start, end, normalized);
                EntityCandidate candidate = new EntityCandidate(id, rule.entityType(), surface,
                        normalized, start, end, rule.confidence());
                if (!result.contains(candidate)) result.add(candidate);
                if (result.size() >= maxCandidates) return ordered(result);
            }
        }
        return ordered(result);
    }

    static String normalize(String value) {
        return value == null ? "" : value.strip().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
    }

    private static String candidateId(KnowledgeExtractionInput input, String type, int start, int end, String normalized) {
        String identity = input.tenantId() + "\n" + input.collectionId() + "\n"
                + input.document().id() + "\n" + input.sourceVersion() + "\n"
                + input.chunk().id() + "\n" + type + "\n" + start + "\n" + end + "\n" + normalized;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8));
            return "mention-" + HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new RagException("SHA-256 is unavailable for candidate identity", e);
        }
    }

    private static List<EntityCandidate> ordered(List<EntityCandidate> values) {
        return values.stream().sorted(Comparator.comparingInt(EntityCandidate::startOffset)
                .thenComparing(EntityCandidate::entityType).thenComparing(EntityCandidate::candidateId)).toList();
    }
}
