package tech.kayys.wayang.rag;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic query mention extraction using trusted, application-configured patterns. */
public final class RegexQueryMentionExtractor implements QueryMentionExtractor {
    public record Rule(String entityType, Pattern pattern, int textGroup, double confidence) {
        public Rule {
            if (entityType == null || entityType.isBlank() || entityType.length() > 120)
                throw new RagException("query entity rule needs an entity type of 1..120 characters");
            if (pattern == null) throw new RagException("query entity rule pattern is required");
            if (textGroup < 0 || textGroup > pattern.matcher("").groupCount())
                throw new RagException("query entity rule text group is outside the pattern groups");
            if (!Double.isFinite(confidence) || confidence < 0 || confidence > 1)
                throw new RagException("query entity confidence must be between 0 and 1");
        }
    }

    private final List<Rule> rules;
    private final int maxCandidates;

    public RegexQueryMentionExtractor(List<Rule> rules, int maxCandidates) {
        if (rules == null || rules.isEmpty()) throw new RagException("at least one query entity rule is required");
        if (maxCandidates < 1 || maxCandidates > 100) throw new RagException("query mention limit must be 1..100");
        this.rules = List.copyOf(rules);
        this.maxCandidates = maxCandidates;
    }

    @Override
    public List<EntityCandidate> extract(String query) {
        if (query == null || query.isBlank()) return List.of();
        if (query.length() > 2_000) throw new RagException("query exceeds 2000 characters");
        List<EntityCandidate> found = new ArrayList<>();
        for (Rule rule : rules) {
            Matcher matcher = rule.pattern().matcher(query);
            while (matcher.find()) {
                String surface = matcher.group(rule.textGroup());
                int start = matcher.start(rule.textGroup());
                int end = matcher.end(rule.textGroup());
                if (surface == null || surface.isBlank() || start < 0 || end <= start) continue;
                String normalized = normalize(surface);
                String id = candidateId(rule.entityType(), start, end, normalized);
                EntityCandidate candidate = new EntityCandidate(id, rule.entityType(), surface,
                        normalized, start, end, rule.confidence());
                if (!found.contains(candidate)) found.add(candidate);
                if (found.size() >= maxCandidates) return ordered(found);
            }
        }
        return ordered(found);
    }

    private static String normalize(String value) {
        return value.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static String candidateId(String type, int start, int end, String name) {
        try {
            String value = type + "\\n" + start + "\\n" + end + "\\n" + name;
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return "query-mention-" + HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException impossible) {
            throw new RagException("SHA-256 is unavailable for query mention identity", impossible);
        }
    }

    private static List<EntityCandidate> ordered(List<EntityCandidate> values) {
        return values.stream().sorted(Comparator.comparingInt(EntityCandidate::startOffset)
                .thenComparing(EntityCandidate::entityType).thenComparing(EntityCandidate::candidateId)).toList();
    }
}
