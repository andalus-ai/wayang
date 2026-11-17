package tech.kayys.wayang.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The {@code guardrails} option of an agent, enforced by Wayang on what the agent <em>returns</em> (independent of the model):
 * <pre>{@code {maxOutputChars: 2000, redact: [email, phone, card], blockedTerms: ["internal use only"]}}</pre>
 * Order of enforcement for every text value of the result: blocked terms (the whole value is replaced), then redaction, then the
 * length cap. {@code maxSteps}, {@code timeoutSeconds} and {@code requireApproval} stay on the spec and are the runtime's to enforce.
 *
 * <p>Redaction is pattern based and best effort (it will miss unusual formats and can over-match); it reduces accidental leaks, it does
 * not certify that no personal data is present.
 */
public record AgentGuardrails(int maxOutputChars, Set<String> redact, List<String> blockedTerms) {

    public static final int MAX_OUTPUT_CHARS = 100_000;
    public static final int MAX_TERMS = 50;
    public static final String BLOCKED = "[withheld by a guardrail]";
    private static final Set<String> KINDS = Set.of("email", "phone", "card");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern PHONE = Pattern.compile("(?<![\\w])\\+?\\d[\\d ().-]{7,16}\\d(?![\\w])");
    private static final Pattern CARD = Pattern.compile("(?<![\\d])(?:\\d[ -]?){12,18}\\d(?![\\d])");

    /** @return null when the agent has no guardrails option; @throws AgentSpecException when it is malformed */
    public static AgentGuardrails of(AgentSpec spec) {
        return of(spec.id(), spec.options());
    }

    static AgentGuardrails of(String agentId, Map<String, Object> options) {
        Object o = options.get("guardrails");
        if (o == null) {
            return null;
        }
        String where = "Agent '" + agentId + "': options.guardrails";
        if (!(o instanceof Map<?, ?> m)) {
            throw new AgentSpecException(where + " must be an object");
        }
        for (Object k : m.keySet()) {
            if (!List.of("maxOutputChars", "redact", "blockedTerms").contains(String.valueOf(k))) {
                throw new AgentSpecException(where + " has an unknown field '" + k + "'");
            }
        }
        int max = 0;
        if (m.get("maxOutputChars") != null) {
            if (!(m.get("maxOutputChars") instanceof Number n) || n.intValue() < 1 || n.intValue() > MAX_OUTPUT_CHARS) {
                throw new AgentSpecException(where + ".maxOutputChars must be 1.." + MAX_OUTPUT_CHARS);
            }
            max = n.intValue();
        }
        Set<String> redact = new java.util.LinkedHashSet<>();
        if (m.get("redact") != null) {
            if (!(m.get("redact") instanceof List<?> l)) {
                throw new AgentSpecException(where + ".redact must be a list of " + KINDS);
            }
            for (Object e : l) {
                if (!(e instanceof String s) || !KINDS.contains(s)) {
                    throw new AgentSpecException(where + ".redact allows only " + new java.util.TreeSet<>(KINDS));
                }
                redact.add(s);
            }
        }
        List<String> terms = new ArrayList<>();
        if (m.get("blockedTerms") != null) {
            if (!(m.get("blockedTerms") instanceof List<?> l) || l.size() > MAX_TERMS) {
                throw new AgentSpecException(where + ".blockedTerms must be a list of up to " + MAX_TERMS + " terms");
            }
            for (Object e : l) {
                if (!(e instanceof String s) || s.isBlank() || s.length() > 100) {
                    throw new AgentSpecException(where + ".blockedTerms entries must be text of 1..100 characters");
                }
                terms.add(s.strip());
            }
        }
        if (max == 0 && redact.isEmpty() && terms.isEmpty()) {
            throw new AgentSpecException(where + " needs at least one of maxOutputChars, redact, blockedTerms");
        }
        return new AgentGuardrails(max, Set.copyOf(redact), List.copyOf(terms));
    }

    /** Applies the guardrails to one text. */
    public String apply(String text) {
        if (text == null) {
            return null;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String t : blockedTerms) {
            if (lower.contains(t.toLowerCase(Locale.ROOT))) {
                return BLOCKED;
            }
        }
        String out = text;
        if (redact.contains("email")) {
            out = EMAIL.matcher(out).replaceAll("[email removed]");
        }
        if (redact.contains("card")) {
            out = CARD.matcher(out).replaceAll(r -> luhn(r.group()) ? "[card removed]" : java.util.regex.Matcher.quoteReplacement(r.group()));
        }
        if (redact.contains("phone")) {
            out = PHONE.matcher(out).replaceAll("[phone removed]");
        }
        if (maxOutputChars > 0 && out.length() > maxOutputChars) {
            out = out.substring(0, maxOutputChars) + "…";
        }
        return out;
    }

    /** Applies the guardrails to every top-level text value of an agent result; other values are untouched. */
    public Map<String, Object> applyTo(Map<String, Object> result) {
        Map<String, Object> out = new LinkedHashMap<>();
        result.forEach((k, v) -> out.put(k, v instanceof String s ? apply(s) : v));
        return out;
    }

    private static boolean luhn(String digits) {
        int sum = 0;
        boolean dbl = false;
        int n = 0;
        for (int i = digits.length() - 1; i >= 0; i--) {
            char c = digits.charAt(i);
            if (!Character.isDigit(c)) {
                continue;
            }
            int d = c - '0';
            if (dbl && (d *= 2) > 9) {
                d -= 9;
            }
            sum += d;
            dbl = !dbl;
            n++;
        }
        return n >= 13 && n <= 19 && sum % 10 == 0;
    }
}
