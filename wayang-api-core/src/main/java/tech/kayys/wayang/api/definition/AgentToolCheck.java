package tech.kayys.wayang.api.definition;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tech.kayys.wayang.agent.AgentSpec;

/**
 * Publish-time checks on an agent's tools that a model can never fix at run time. Findings name the tool and the config path, and
 * never repeat a value (it may be a credential somebody pasted).
 *
 * <ul>
 * <li>a tool needs a {@code type} (the connector it calls);</li>
 * <li>{@code config.parameters}, when present, is a JSON object (the schema shown to the model);</li>
 * <li>a string under a credential-looking key (token, secret, password, api key, authorization, cookie) must be made of
 * {@code ${secret:NAME}} / {@code ${config:NAME}} references, optionally with a {@code Bearer} or {@code Basic} prefix, never a literal;</li>
 * <li>every {@code ${...}} must be a well-formed reference.</li>
 * </ul>
 *
 * It cannot know whether a named secret exists or a connector type is wired in the release: both stay run-time answers.
 */
public final class AgentToolCheck {

    private static final Pattern REF = Pattern.compile("\\$\\{(secret|config):[A-Za-z0-9_.-]{1,100}}");
    private static final Pattern CREDENTIAL_KEY = Pattern.compile("(?i)(.*(secret|password|passwd|apikey|api_key|authorization|credential|cookie).*|.*tokens?)");
    private static final Pattern COUNTER_KEY = Pattern.compile("(?i)(max|min|num).*|.*(count|limit|size)");

    private AgentToolCheck() {
    }

    public static List<ValidationReport.Item> check(AgentSpec spec) {
        List<ValidationReport.Item> out = new ArrayList<>();
        for (AgentSpec.Tool t : spec.tools()) {
            if (t.type() == null || t.type().isBlank()) {
                out.add(error(t, "has no type: name the connector it calls"));
            }
            Object params = t.config().get("parameters");
            if (params != null && !(params instanceof Map)) {
                out.add(error(t, "config.parameters must be a JSON object (a schema)"));
            }
            walk(t, "config", t.config(), null, out);
        }
        return out;
    }

    private static void walk(AgentSpec.Tool t, String path, Object v, String key, List<ValidationReport.Item> out) {
        if (v instanceof Map<?, ?> m) {
            if ("parameters".equals(key)) {
                return; // the model-facing schema: descriptions and examples, not connector settings
            }
            m.forEach((k, x) -> walk(t, path + "." + k, x, String.valueOf(k), out));
        } else if (v instanceof List<?> l) {
            for (int i = 0; i < l.size(); i++) {
                walk(t, path + "[" + i + "]", l.get(i), key, out);
            }
        } else if (v instanceof String s) {
            String rest = REF.matcher(s).replaceAll("");
            if (rest.contains("${")) {
                out.add(error(t, path + " has a malformed ${...} reference (use ${secret:NAME} or ${config:NAME})"));
            } else if (key != null && CREDENTIAL_KEY.matcher(key).matches() && !COUNTER_KEY.matcher(key).matches()
                    && !s.isEmpty() && !isReferenceOnly(s, rest)) {
                out.add(error(t, path + " looks like a credential and holds a literal value: store it as a secret and use ${secret:NAME}"));
            }
        }
    }

    private static boolean isReferenceOnly(String s, String rest) {
        Matcher m = REF.matcher(s);
        if (!m.find()) {
            return false;
        }
        String r = rest.strip().toLowerCase(java.util.Locale.ROOT);
        return r.isEmpty() || r.equals("bearer") || r.equals("basic");
    }

    private static ValidationReport.Item error(AgentSpec.Tool t, String message) {
        return new ValidationReport.Item("ERROR", null, "Tool '" + t.name() + "' " + message);
    }
}
