package tech.kayys.wayang.embed;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tech.kayys.wayang.agent.AgentSpec;
import tech.kayys.wayang.agent.AgentSpecLoader;
import tech.kayys.wayang.runtime.SecretResolver;

/**
 * {@code doctor}: what is wrong or risky about this release's setup, before anyone depends on it. The static checks read the bundle and the
 * environment only. With {@code --live} it also makes one harmless read-only call per model provider and per Telegram or Slack token the
 * release uses, to learn whether the key or token is accepted.
 *
 * <p>A finding names the thing (a secret's name, a trigger id), never a value: nothing a probe sends or receives that could hold a secret is
 * printed. {@link Level#FAIL} means the release will not work or is unsafe; {@link Level#WARN} means it will work but loses something;
 * {@link Level#INFO} is for the next step.
 */
public final class Doctor {

    public enum Level {
        OK, INFO, WARN, FAIL
    }

    public record Finding(Level level, String id, String message) {
        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("level", level.name());
            m.put("id", id);
            m.put("message", message);
            return m;
        }
    }

    /** How a probe reaches the network; replaced in tests. */
    @FunctionalInterface
    public interface Http {
        /** @return the status and the first part of the body; @throws IOException when the host cannot be reached */
        Reply send(String method, URI url, Map<String, String> headers) throws IOException;
    }

    public record Reply(int status, String body) {
    }

    private static final Pattern SECRET = Pattern.compile("\\$\\{secret:([A-Za-z0-9_.-]{1,100})}");
    private static final Map<String, String> BASES = Map.of(
            "openai", "https://api.openai.com/v1", "openrouter", "https://openrouter.ai/api/v1", "groq", "https://api.groq.com/openai/v1",
            "together", "https://api.together.xyz/v1", "mistral", "https://api.mistral.ai/v1", "deepseek", "https://api.deepseek.com/v1",
            "ollama", "http://localhost:11434/v1", "vllm", "http://localhost:8000/v1", "anthropic", "https://api.anthropic.com");

    private final ReleaseEngine rt;
    private final Function<String, String> env;
    private final SecretResolver secrets;
    private final Http http;
    private final List<Finding> out = new ArrayList<>();

    public Doctor(ReleaseEngine rt, Function<String, String> env, SecretResolver secrets, Http http) {
        this.rt = rt;
        this.env = env;
        this.secrets = secrets;
        this.http = http;
    }

    private void add(Level l, String id, String msg) {
        out.add(new Finding(l, id, msg));
    }

    public List<Finding> run(boolean live) {
        out.clear();
        ReleaseBundle b = rt.bundle();
        secretsCheck();
        stateCheck(b);
        apiKeyCheck();
        Map<String, AgentSpec> agents = agents(b);
        modelCheck(agents);
        triggerCheck(b);
        schedulesInfo(b);
        if (live) {
            liveChecks(b, agents);
        } else {
            add(Level.INFO, "live", "Run `doctor --live` to check that the model keys and chat tokens are accepted (one read-only call each).");
        }
        return List.copyOf(out);
    }

    /** 0 when nothing failed, 1 otherwise. */
    public static int exitCode(List<Finding> findings) {
        return findings.stream().anyMatch(f -> f.level() == Level.FAIL) ? 1 : 0;
    }

    // ---------------------------------------------------------------- static

    private void secretsCheck() {
        var missing = rt.missingSecrets();
        if (missing.isEmpty()) {
            add(Level.OK, "secrets", "Every secret the release needs is set.");
        }
        for (String m : missing) {
            add(Level.FAIL, "secret:" + m, "The secret '" + m + "' is not set (WAYANG_SECRET_" + m.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "_") + " or a file in WAYANG_SECRETS_DIR).");
        }
    }

    private void stateCheck(ReleaseBundle b) {
        boolean needsState = b.triggers().stream().anyMatch(t -> !t.webhook()) || b.definitions().stream().anyMatch(d -> d.kind().equals("AGENT") && d.document().get("options") instanceof Map<?, ?> o && o.get("memory") != null);
        String dir = env.apply("WAYANG_STATE_DIR");
        if (dir == null || dir.isBlank()) {
            if (needsState) {
                add(Level.WARN, "state", "WAYANG_STATE_DIR is not set: poll cursors, the last run of schedules and conversation memory are lost on restart (messages may be read again).");
            }
            return;
        }
        try {
            Path p = Path.of(dir);
            Files.createDirectories(p);
            Path t = Files.createTempFile(p, "doctor-", ".tmp");
            Files.delete(t);
            add(Level.OK, "state", "WAYANG_STATE_DIR is writable. Only one running copy of the release should use it.");
        } catch (IOException | RuntimeException e) {
            add(Level.FAIL, "state", "WAYANG_STATE_DIR is not writable (" + e.getClass().getSimpleName() + ").");
        }
    }

    private void apiKeyCheck() {
        boolean key = notBlank(env.apply("WAYANG_API_KEY")) || notBlank(env.apply("WAYANG_API_KEY_FILE"));
        String bind = env.apply("WAYANG_BIND");
        if (!key) {
            add(Level.WARN, "api-key", "No WAYANG_API_KEY or WAYANG_API_KEY_FILE: the run, task and trigger-status endpoints are open to whoever can reach "
                    + (bind == null ? "0.0.0.0" : bind) + ".");
        } else {
            add(Level.OK, "api-key", "The HTTP API needs a key.");
        }
    }

    private Map<String, AgentSpec> agents(ReleaseBundle b) {
        Map<String, AgentSpec> m = new LinkedHashMap<>();
        for (ReleaseBundle.Definition d : b.definitions()) {
            if (d.kind().equals("AGENT")) {
                try {
                    m.put(d.id(), AgentSpecLoader.fromMap(d.document()));
                } catch (RuntimeException e) {
                    add(Level.FAIL, "agent:" + d.id(), "The agent definition is invalid: " + e.getMessage());
                }
            }
        }
        return m;
    }

    private void modelCheck(Map<String, AgentSpec> agents) {
        if (agents.isEmpty()) {
            return;
        }
        for (String p : providers(agents)) {
            String url = env.apply("WAYANG_LLM_" + p.toUpperCase(Locale.ROOT) + "_URL");
            if (notBlank(url)) {
                try {
                    URI u = URI.create(url.strip());
                    if (u.getScheme() == null || u.getHost() == null || !(u.getScheme().equals("https") || u.getScheme().equals("http"))) {
                        throw new IllegalArgumentException();
                    }
                    if (u.getScheme().equals("http") && !isLocal(u.getHost())) {
                        add(Level.WARN, "model:" + p, "WAYANG_LLM_" + p.toUpperCase(Locale.ROOT) + "_URL uses plain http to a non-local host: the model key travels unencrypted.");
                    }
                } catch (IllegalArgumentException e) {
                    add(Level.FAIL, "model:" + p, "WAYANG_LLM_" + p.toUpperCase(Locale.ROOT) + "_URL is not a valid http(s) address.");
                }
            }
            if (!BASES.containsKey(p)) {
                add(Level.FAIL, "model:" + p, "The model provider '" + p + "' is not supported.");
            }
        }
        String daily = env.apply("WAYANG_LLM_DAILY_TOKENS");
        try {
            TokenBudget.parse(daily, java.time.Clock.systemUTC());
            boolean perRun = agents.values().stream().allMatch(a -> a.options().get("maxTotalTokens") instanceof Number);
            if (!notBlank(daily) && !perRun) {
                add(Level.WARN, "spend", "No token cap: set WAYANG_LLM_DAILY_TOKENS, or maxTotalTokens on every agent, so a loop or a flood of messages cannot run up the bill.");
            } else {
                add(Level.OK, "spend", "A token cap is set" + (notBlank(daily) ? " (" + daily.strip() + " per UTC day)." : " per run."));
            }
        } catch (IllegalArgumentException e) {
            add(Level.FAIL, "spend", e.getMessage());
        }
    }

    private static boolean isLocal(String host) {
        return host.equals("localhost") || host.equals("127.0.0.1") || host.equals("::1") || host.equals("[::1]");
    }

    private static List<String> providers(Map<String, AgentSpec> agents) {
        return new ArrayList<>(new TreeSet<>(agents.values().stream().map(a -> LlmAgentInvoker.provider(a.model())).filter(p -> p != null).toList()));
    }

    private void triggerCheck(ReleaseBundle b) {
        boolean userTasks = b.definitions().stream().anyMatch(d -> d.kind().equals("PROCESS") && String.valueOf(d.document().get("elements")).contains("USER_TASK"));
        for (ReleaseBundle.Trigger t : b.triggers()) {
            Object src = t.config().get("source") != null ? t.config().get("source") : t.config().get("auth");
            boolean telegram = "telegram".equals(src);
            boolean slack = "slack".equals(src);
            if (telegram && !(t.config().get("allowedChatIds") instanceof List<?> l && !l.isEmpty())) {
                add(Level.FAIL, "trigger:" + t.id(), "Telegram trigger without allowedChatIds: anyone who finds the bot could talk to it.");
            }
            if (slack && !(t.config().get("allowedChannels") instanceof List<?> l && !l.isEmpty())) {
                add(Level.FAIL, "trigger:" + t.id(), "Slack trigger without allowedChannels: any channel the app is in could talk to it.");
            }
            if (telegram || slack) {
                if (t.config().get("rateLimit") == null) {
                    add(Level.WARN, "trigger:" + t.id(), "No rateLimit on a chat trigger: a flood of messages is a flood of model calls.");
                }
                if (userTasks && !Boolean.TRUE.equals(t.config().get("approvals"))) {
                    add(Level.WARN, "trigger:" + t.id(), "The workflows have approval tasks but this trigger has no approvals: a yes in the chat will not answer them (only the HTTP API can).");
                }
            }
            if (t.kind().equals("WEBHOOK") && t.provider() == null && !secretSet("trigger." + t.id())) {
                add(Level.FAIL, "trigger:" + t.id(), "Webhook without its shared secret 'trigger." + t.id() + "': every call is refused.");
            }
        }
    }

    private boolean secretSet(String name) {
        return !rt.missingSecrets().contains(name);
    }

    private void schedulesInfo(ReleaseBundle b) {
        for (ReleaseBundle.Trigger t : b.triggers()) {
            if (t.schedule()) {
                add(Level.INFO, "schedule:" + t.id(), "Runs '" + t.config().get("cron") + "' in " + (t.config().get("timezone") == null ? "UTC" : t.config().get("timezone"))
                        + ". Run exactly one copy of this release, or each copy will run it.");
            }
        }
    }

    // ------------------------------------------------------------------ live

    private void liveChecks(ReleaseBundle b, Map<String, AgentSpec> agents) {
        for (String p : providers(agents)) {
            probeModel(p);
        }
        Map<String, String> telegram = new LinkedHashMap<>();
        Map<String, String> slack = new LinkedHashMap<>();
        b.definitions().forEach(d -> collectTokens(d.document(), telegram, slack));
        b.triggers().forEach(t -> collectTokens(t.config(), telegram, slack));
        telegram.forEach((name, where) -> probeTelegram(name));
        slack.forEach((name, where) -> probeSlack(name));
        if (telegram.isEmpty() && slack.isEmpty() && agents.isEmpty()) {
            add(Level.INFO, "live", "Nothing to probe: the release uses no model and no Telegram or Slack token.");
        }
    }

    private static void collectTokens(Object o, Map<String, String> telegram, Map<String, String> slack) {
        if (o instanceof Map<?, ?> m) {
            Object connector = m.get("connector") != null ? m.get("connector") : m.get("source");
            if (m.get("botToken") instanceof String s) {
                Matcher x = SECRET.matcher(s);
                if (x.matches() && "telegram".equals(connector)) {
                    telegram.putIfAbsent(x.group(1), "telegram");
                } else if (x.matches() && "slack".equals(connector)) {
                    slack.putIfAbsent(x.group(1), "slack");
                }
            }
            m.values().forEach(v -> collectTokens(v, telegram, slack));
        } else if (o instanceof List<?> l) {
            l.forEach(v -> collectTokens(v, telegram, slack));
        }
    }

    private void probeModel(String p) {
        String id = "live:model:" + p;
        String base = env.apply("WAYANG_LLM_" + p.toUpperCase(Locale.ROOT) + "_URL");
        base = (notBlank(base) ? base.strip() : BASES.get(p));
        if (base == null) {
            return;
        }
        base = base.replaceAll("/+$", "");
        Map<String, String> h = new LinkedHashMap<>();
        String key = null;
        if (LlmAgentInvoker.needsKey(p)) {
            key = secrets == null ? null : secrets.resolve(ReleaseEngine.TENANT, LlmAgentInvoker.keyName(p));
            if (key == null || key.isEmpty()) {
                add(Level.FAIL, id, "No key for " + p + " (the secret " + LlmAgentInvoker.keyName(p) + "), so nothing to try.");
                return;
            }
            if (p.equals("anthropic")) {
                h.put("x-api-key", key);
                h.put("anthropic-version", "2023-06-01");
            } else {
                h.put("Authorization", "Bearer " + key);
            }
        }
        String url = p.equals("anthropic") ? base + "/v1/models" : base + "/models";
        report(id, "the " + p + " model endpoint", url, "GET", h, null, key);
    }

    private void probeTelegram(String secretName) {
        String token = secrets == null ? null : secrets.resolve(ReleaseEngine.TENANT, secretName);
        if (token == null || token.isEmpty()) {
            add(Level.FAIL, "live:telegram:" + secretName, "The secret " + secretName + " is not set, so the bot cannot be checked.");
            return;
        }
        report("live:telegram:" + secretName, "Telegram (" + secretName + ")", "https://api.telegram.org/bot" + token + "/getMe", "GET", Map.of(), "\"ok\":true", token);
    }

    private void probeSlack(String secretName) {
        String token = secrets == null ? null : secrets.resolve(ReleaseEngine.TENANT, secretName);
        if (token == null || token.isEmpty()) {
            add(Level.FAIL, "live:slack:" + secretName, "The secret " + secretName + " is not set, so the app cannot be checked.");
            return;
        }
        report("live:slack:" + secretName, "Slack (" + secretName + ")", "https://slack.com/api/auth.test", "POST", Map.of("Authorization", "Bearer " + token), "\"ok\":true", token);
    }

    /** One probe. The URL may hold a token, so only the label, the status and a verdict are ever printed. */
    private void report(String id, String label, String url, String method, Map<String, String> headers, String mustContain, String secret) {
        try {
            Reply r = http.send(method, URI.create(url), headers);
            if (r.status() == 401 || r.status() == 403) {
                add(Level.FAIL, id, label + " rejected the credentials (HTTP " + r.status() + ").");
            } else if (r.status() < 200 || r.status() >= 300) {
                add(Level.FAIL, id, label + " answered HTTP " + r.status() + ".");
            } else if (mustContain != null && (r.body() == null || !r.body().replace(" ", "").contains(mustContain))) {
                add(Level.FAIL, id, label + " answered, but not with success: the token is probably wrong or revoked.");
            } else {
                add(Level.OK, id, label + " accepted the credentials.");
            }
        } catch (IOException | RuntimeException e) {
            add(Level.FAIL, id, label + " could not be reached (" + e.getClass().getSimpleName() + ").");
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    // ---------------------------------------------------------------- output

    public static String text(List<Finding> findings) {
        StringBuilder sb = new StringBuilder();
        for (Finding f : findings) {
            sb.append(switch (f.level()) {
                case OK -> "[ ok ] ";
                case INFO -> "[info] ";
                case WARN -> "[warn] ";
                case FAIL -> "[FAIL] ";
            }).append(f.id()).append(": ").append(f.message()).append('\n');
        }
        long fails = findings.stream().filter(f -> f.level() == Level.FAIL).count();
        long warns = findings.stream().filter(f -> f.level() == Level.WARN).count();
        sb.append(fails == 0 ? (warns == 0 ? "Ready." : "Works, with " + warns + " warning(s).") : fails + " problem(s) to fix before this release can be relied on.").append('\n');
        return sb.toString();
    }

    /** The probe transport used by the command line: 10 s, no redirects followed, body cut to 4 KB. */
    public static Http jdkHttp() {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
        return (method, url, headers) -> {
            HttpRequest.Builder rb = HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(10));
            headers.forEach(rb::header);
            rb.method(method, HttpRequest.BodyPublishers.noBody());
            try {
                HttpResponse<byte[]> r = client.send(rb.build(), HttpResponse.BodyHandlers.ofByteArray());
                byte[] body = r.body();
                return new Reply(r.statusCode(), new String(body, 0, Math.min(body.length, 4096), StandardCharsets.UTF_8));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted");
            }
        };
    }
}
