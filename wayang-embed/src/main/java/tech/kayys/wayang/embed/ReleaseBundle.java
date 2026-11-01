package tech.kayys.wayang.embed;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tech.kayys.wayang.runtime.json.Json;

/**
 * The published workspace bundle a release carries (format {@code wayang.workspace}), read back into the parts a runtime needs.
 * The bundle was verified when the release was generated; here it is only parsed, and anything unexpected is refused.
 */
public final class ReleaseBundle {

    public record Definition(String kind, String id, int version, Map<String, Object> document) {
    }

    /** A trigger a release carries: a webhook route {@code /hooks/<id>} or a poll loop. Only enabled triggers are kept. */
    public record Trigger(String id, String kind, String processId, Map<String, Object> config) {

        public boolean webhook() {
            return kind.equals("WEBHOOK");
        }

        /** A cron schedule: it starts the process by the clock, with no event source. */
        public boolean schedule() {
            return kind.equals("SCHEDULE");
        }

        /** The provider named by {@code config.auth} (slack, whatsapp, github), or null when the trigger uses a shared secret. */
        public String provider() {
            return config.get("auth") instanceof String a && !a.equals("secret") ? a : null;
        }

        /** The secret name of a shared-secret webhook, null for the others. */
        public String secretName() {
            return webhook() && provider() == null ? secretNameFor(id) : null;
        }
    }

    /** The name under which a release looks up the shared secret of webhook trigger {@code id}. */
    public static String secretNameFor(String id) {
        return "trigger." + id;
    }

    private static final java.util.regex.Pattern TRIGGER_ID = java.util.regex.Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private final String name;
    private final List<Trigger> triggers;
    private final List<Definition> definitions;
    private final Map<String, String> config;
    private final List<String> requiredSecrets;
    private final List<String> requiredConfig;
    private final String sha256;

    private ReleaseBundle(String name, List<Definition> defs, List<Trigger> triggers, Map<String, String> config, List<String> secrets,
            List<String> cfgNames, String sha) {
        this.name = name;
        this.triggers = List.copyOf(triggers);
        this.definitions = List.copyOf(defs);
        this.config = Map.copyOf(config);
        this.requiredSecrets = List.copyOf(secrets);
        this.requiredConfig = List.copyOf(cfgNames);
        this.sha256 = sha;
    }

    public String name() {
        return name;
    }

    public List<Definition> definitions() {
        return definitions;
    }

    public List<Trigger> triggers() {
        return triggers;
    }

    /** Settings carried by the bundle: tenant-wide ones first, then the project's, which win. */
    public Map<String, String> config() {
        return config;
    }

    public List<String> requiredSecrets() {
        return requiredSecrets;
    }

    public List<String> requiredConfig() {
        return requiredConfig;
    }

    /** The bundle's own integrity hash, for display; it is not re-checked here. */
    public String sha256() {
        return sha256;
    }

    public static ReleaseBundle parse(String json) {
        Object root;
        try {
            root = Json.read(json);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("The release bundle is not valid JSON");
        }
        if (!(root instanceof Map<?, ?> m) || !"wayang.workspace".equals(m.get("format")) || !(m.get("formatVersion") instanceof Number v) || v.intValue() != 1) {
            throw new IllegalArgumentException("The release bundle is not a wayang.workspace bundle of format version 1");
        }
        List<Definition> defs = new ArrayList<>();
        if (m.get("definitions") instanceof List<?> l) {
            for (Object o : l) {
                if (!(o instanceof Map<?, ?> d) || !(d.get("kind") instanceof String kind) || !(d.get("id") instanceof String id)
                        || !(d.get("document") instanceof Map<?, ?> doc)) {
                    throw new IllegalArgumentException("A definition in the release bundle is malformed");
                }
                int ver = d.get("publishedVersion") instanceof Number n ? n.intValue() : 0;
                if (ver < 1) {
                    throw new IllegalArgumentException("Definition " + id + " has no published version: a release carries published versions only");
                }
                defs.add(new Definition(kind, id, ver, castMap(doc)));
            }
        }
        java.util.Set<String> processIds = new java.util.HashSet<>();
        for (Definition d : defs) {
            if (d.kind().equals("PROCESS")) {
                processIds.add(d.id());
            }
        }
        for (Definition d : defs) {
            if (d.kind().equals("PROCESS")) {
                for (String target : tech.kayys.wayang.eip.CollectSweeper.sweepTargets(d.document().get("elements"))) {
                    if (!processIds.contains(target)) {
                        throw new IllegalArgumentException("Process " + d.id() + " has a collect that sweeps to process " + target + ", which the release does not carry");
                    }
                }
            }
        }
        List<Trigger> triggers = new ArrayList<>();
        java.util.Set<String> triggerIds = new java.util.HashSet<>();
        if (m.get("triggers") instanceof List<?> tl) {
            for (Object o : tl) {
                if (!(o instanceof Map<?, ?> t) || !(t.get("id") instanceof String id) || !TRIGGER_ID.matcher(id).matches()
                        || !(t.get("kind") instanceof String kind) || !(kind.equals("WEBHOOK") || kind.equals("POLL") || kind.equals("SCHEDULE"))
                        || !(t.get("processId") instanceof String proc) || !triggerIds.add(id)) {
                    throw new IllegalArgumentException("A trigger in the release bundle is malformed or repeated");
                }
                if (!processIds.contains(proc)) {
                    throw new IllegalArgumentException("Trigger " + id + " points at process " + proc + ", which the release does not carry");
                }
                if (Boolean.FALSE.equals(t.get("enabled"))) {
                    continue;
                }
                Map<String, Object> tc = t.get("config") instanceof Map<?, ?> c ? castMap(c) : Map.of();
                if (tc.get("approvals") != null && !(tc.get("approvals") instanceof Boolean ab && (!ab || tech.kayys.wayang.runtime.ChatApproval.conversationField(tc) != null))) {
                    throw new IllegalArgumentException("Trigger " + id + ": approvals must be true or false, and true is for Telegram and Slack triggers only");
                }
                try {
                    tech.kayys.wayang.runtime.RateLimiter.fromTriggerConfig(tc);
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("Trigger " + id + ": " + e.getMessage());
                }
                if (kind.equals("SCHEDULE")) {
                    try {
                        tech.kayys.wayang.runtime.CronSchedule.parse(tc.get("cron") instanceof String c ? c : null, tc.get("timezone") instanceof String z ? z : null);
                    } catch (IllegalArgumentException e) {
                        throw new IllegalArgumentException("Trigger " + id + ": " + e.getMessage());
                    }
                }
                triggers.add(new Trigger(id, kind, proc, tc));
            }
        }
        Map<String, String> cfg = new LinkedHashMap<>();
        if (m.get("config") instanceof Map<?, ?> c) {
            for (String scope : new String[] { "tenant", "project" }) { // the project's value wins
                if (c.get(scope) instanceof Map<?, ?> s) {
                    s.forEach((k, val) -> {
                        if (k instanceof String ks && val instanceof String vs) {
                            cfg.put(ks, vs);
                        }
                    });
                }
            }
        }
        List<String> secrets = new ArrayList<>();
        List<String> cfgNames = new ArrayList<>();
        if (m.get("requires") instanceof Map<?, ?> r) {
            strings(r.get("secrets"), secrets);
            strings(r.get("externalSecrets"), secrets);
            strings(r.get("oauthConnections"), secrets);
            strings(r.get("config"), cfgNames);
        }
        for (String model : agentModels(defs)) { // the key of each model provider is supplied by the environment, never by the bundle
            String p = LlmAgentInvoker.provider(model);
            if (p != null && LlmAgentInvoker.needsKey(p)) {
                secrets.add(LlmAgentInvoker.keyName(p));
            }
        }
        for (Trigger t : triggers) {
            if (t.secretName() != null) {
                secrets.add(t.secretName()); // the shared secret of a webhook is supplied by the environment, never by the bundle
            }
        }
        String name = m.get("project") instanceof Map<?, ?> p && p.get("name") instanceof String s ? s : "release";
        String sha = m.get("integrity") instanceof Map<?, ?> i && i.get("sha256") instanceof String s ? s : "";
        return new ReleaseBundle(name, defs, triggers, cfg, secrets, cfgNames, sha);
    }

    /** The {@code model} of every agent: AGENT definitions, and agents written inline in a process node. */
    private static java.util.Set<String> agentModels(List<Definition> defs) {
        java.util.Set<String> out = new java.util.TreeSet<>();
        for (Definition d : defs) {
            if (d.kind().equals("AGENT") && d.document().get("model") instanceof String m) {
                out.add(m);
            } else if (d.kind().equals("PROCESS")) {
                inlineAgents(d.document().get("elements"), out);
            }
        }
        return out;
    }

    private static void inlineAgents(Object o, java.util.Set<String> out) {
        if (o instanceof Map<?, ?> m) {
            if (m.get("agent") instanceof Map<?, ?> a && a.get("model") instanceof String model) {
                out.add(model);
            }
            m.values().forEach(v -> inlineAgents(v, out));
        } else if (o instanceof List<?> l) {
            l.forEach(v -> inlineAgents(v, out));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }

    private static void strings(Object o, List<String> into) {
        if (o instanceof List<?> l) {
            for (Object x : l) {
                if (x instanceof String s) {
                    into.add(s);
                }
            }
        }
    }
}
