package tech.kayys.wayang.embed;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import tech.kayys.wayang.runtime.ConfigResolver;
import tech.kayys.wayang.runtime.SecretResolver;
import tech.kayys.wayang.runtime.ServiceHandler;

/**
 * The connector {@code agent-actions}: runs the actions an agent <em>proposed</em> (its {@code pendingCalls}) once a person has approved them.
 *
 * <p>An agent tool listed in {@code requireApproval} is not run when the model calls it; the call is recorded. A process asks the person (a user
 * task, answered in the chat with {@code approvals: true}) and, on yes, passes {@code draft.pendingCalls} as input {@code calls} here. Each call
 * carries the tool's connector type, its configuration still holding {@code ${secret:..}}/{@code ${config:..}} references, and the model's
 * arguments; the references are looked up now, so no secret value ever sits in a run's variables.
 *
 * <p>Missing or empty {@code calls} is a no-op, so a process may run this step on every path. At most {@value #MAX_CALLS} calls, never this
 * connector itself, never a connector the release does not have. Failures are reported per call (without secret values) and do not stop the
 * others.
 */
public final class AgentActions implements ServiceHandler.Connector {

    public static final String NAME = "agent-actions";
    static final int MAX_CALLS = 10;

    private final Map<String, ServiceHandler.Connector> connectors;
    private final SecretResolver secrets;
    private final ConfigResolver config;

    public AgentActions(Map<String, ServiceHandler.Connector> connectors, SecretResolver secrets, ConfigResolver config) {
        this.connectors = Map.copyOf(connectors);
        this.secrets = secrets;
        this.config = config;
    }

    /** The release's connectors plus this one. */
    public static Map<String, ServiceHandler.Connector> with(Map<String, ServiceHandler.Connector> connectors, SecretResolver secrets, ConfigResolver config) {
        Map<String, ServiceHandler.Connector> all = new java.util.LinkedHashMap<>(connectors);
        all.put(NAME, new AgentActions(connectors, secrets, config));
        return all;
    }

    @Override
    public Map<String, Object> call(Map<String, Object> inputs) {
        Object raw = inputs.get("calls");
        if (raw == null) {
            return Map.of("done", 0, "failed", 0, "results", List.of());
        }
        if (!(raw instanceof List<?> calls) || calls.size() > MAX_CALLS) {
            throw new IllegalArgumentException("calls must be a list of at most " + MAX_CALLS + " actions");
        }
        List<Object> results = new ArrayList<>();
        int done = 0;
        for (Object o : calls) {
            Map<String, Object> r = new java.util.LinkedHashMap<>();
            results.add(r);
            if (!(o instanceof Map<?, ?> c) || !(c.get("type") instanceof String type) || !(c.get("args") instanceof Map<?, ?> args)
                    || !(c.get("config") instanceof Map<?, ?> cfg0)) {
                r.put("ok", false);
                r.put("error", "not an action");
                continue;
            }
            r.put("tool", String.valueOf(c.get("tool")));
            ServiceHandler.Connector connector = NAME.equals(type) ? null : connectors.get(type);
            if (connector == null) {
                r.put("ok", false);
                r.put("error", "no connector '" + type + "' in this release");
                continue;
            }
            Set<String> used = new HashSet<>();
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> cfg = (Map<String, Object>) LlmAgentInvoker.refs(secrets, config, cfg0, used);
                @SuppressWarnings("unchecked")
                Map<String, Object> a = (Map<String, Object>) args;
                Map<String, Object> out = connector.call(cfg, a);
                r.put("ok", true);
                r.put("output", out == null ? Map.of() : out);
                done++;
            } catch (Exception e) {
                r.put("ok", false);
                r.put("error", LlmAgentInvoker.cut(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), used));
            }
        }
        return Map.of("done", done, "failed", calls.size() - done, "results", results);
    }
}
