package tech.kayys.wayang.api.trigger;

import tech.kayys.wayang.api.ApiException;
import tech.kayys.wayang.api.Permission;
import tech.kayys.wayang.api.Principal;
import tech.kayys.wayang.api.run.RunStatus;
import tech.kayys.wayang.api.run.RunView;
import tech.kayys.wayang.api.run.TaskView;
import tech.kayys.wayang.api.TenantGate;
import tech.kayys.wayang.api.WayangApi;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tech.kayys.wayang.runtime.Poller;
import tech.kayys.wayang.runtime.SecretResolver;
import tech.kayys.wayang.runtime.WebhookProvider;
import tech.kayys.wayang.runtime.json.Json;

/**
 * Starts processes in response to outside events.
 *
 * <ul>
 *   <li><b>Webhook</b>: {@code fire()} with the trigger's secret (shown once at creation, stored only as a hash,
 *       compared in constant time). Unknown trigger and wrong secret are the same error.</li>
 *   <li><b>Poll</b>: {@link #tick()} (called by a scheduler) asks the named {@link Poller} for new events and starts
 *       one run per event. The cursor advances only after every event of the batch started a run: delivery is
 *       <b>at-least-once</b>, a crash or a failed start re-delivers the batch, so processes should tolerate a repeat.
 *       Failures back off exponentially (capped at one hour) and are visible on the trigger ({@code lastError}).</li>
 * </ul>
 *
 * A trigger runs processes as a principal holding only {@code RUN_START} in its tenant. Creating one needs both
 * {@code DEFINITION_WRITE} and {@code RUN_START}, so nobody can start a process through a trigger that they could not
 * start directly. Single node: two nodes ticking would poll twice (no lease yet).
 */
public final class TriggerService {

    public static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Pattern SECRET_REF = Pattern.compile("\\$\\{secret:([A-Za-z0-9_.-]{1,100})}");
    private static final int MAX_PAYLOAD_CHARS = 64 * 1024;
    private static final Duration MAX_BACKOFF = Duration.ofHours(1);

    private final TriggerStore store;
    private final WayangApi api;
    private final Map<String, Poller> pollers;
    private final SecretResolver secrets;
    private final Clock clock;
    private final Map<String, WebhookProvider> providers;
    private final SecureRandom random = new SecureRandom();
    private volatile TenantGate gate = TenantGate.ALLOW_ALL;
    /** Recently handled provider event ids per trigger, so provider retries do not start a run twice (in memory, bounded). */
    private final Map<String, java.util.LinkedHashSet<String>> recent = new java.util.HashMap<>();
    private static final int MAX_RECENT = 5000;
    private static final int MAX_BODY_BYTES = 1 << 20;
    /** Config keys holding credentials: must be {@code ${secret:NAME}} references, never literal values. */
    private static final Pattern SENSITIVE_KEY = Pattern.compile("(?i).*(token|secret|password|apikey|api_key).*");
    private static final Pattern PURE_SECRET_REF = Pattern.compile("\\$\\{secret:[A-Za-z0-9_.-]{1,100}}");

    public TriggerService(TriggerStore store, WayangApi api, Map<String, Poller> pollers, SecretResolver secrets, Clock clock) {
        this(store, api, pollers, Map.of(), secrets, clock);
    }

    public TriggerService(TriggerStore store, WayangApi api, Map<String, Poller> pollers, Map<String, WebhookProvider> providers,
            SecretResolver secrets, Clock clock) {
        this.store = store;
        this.api = api;
        this.pollers = Map.copyOf(pollers);
        this.providers = Map.copyOf(providers);
        this.secrets = secrets;
        this.clock = clock;
    }

    /** Result of creating a trigger; {@code webhookSecret} is non-null only for webhooks and never shown again. */
    public record Created(Trigger trigger, String webhookSecret) {
    }

    public Created create(Principal p, String id, Trigger.Kind kind, String processId, Map<String, Object> config) {
        need(p, Permission.DEFINITION_WRITE);
        need(p, Permission.RUN_START);
        if (id == null || !ID.matcher(id).matches()) {
            throw ApiException.badRequest("Trigger id must match " + ID.pattern());
        }
        if (processId == null || processId.isBlank()) {
            throw ApiException.badRequest("processId is required");
        }
        Map<String, Object> cfg = config == null ? Map.of() : config;
        requireSecretRefs(cfg);
        validateApprovals(kind, cfg);
        validateRatings(kind, cfg);
        try {
            tech.kayys.wayang.runtime.RateLimiter.fromTriggerConfig(cfg);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        }
        if (cfg.get("rateLimit") != null && kind == Trigger.Kind.SCHEDULE) {
            throw ApiException.badRequest("a schedule needs no rateLimit");
        }
        String secret = null;
        String hash = null;
        if (kind == Trigger.Kind.SCHEDULE) {
            validateSchedule(cfg);
        } else if (kind == Trigger.Kind.POLL) {
            Object source = cfg.get("source");
            if (!(source instanceof String s) || !pollers.containsKey(s)) {
                throw ApiException.badRequest("config.source must be one of " + pollers.keySet());
            }
            if (cfg.get("everySeconds") != null && !(cfg.get("everySeconds") instanceof Number n && n.longValue() >= 5 && n.longValue() <= 86_400)) {
                throw ApiException.badRequest("everySeconds must be between 5 and 86400");
            }
        } else if (cfg.get("auth") instanceof String auth && !auth.equals("secret")) {
            if (!providers.containsKey(auth)) {
                throw ApiException.badRequest("config.auth must be 'secret' or one of " + providers.keySet());
            }
            // authenticated by the provider's signature, so there is no Wayang secret
        } else {
            byte[] raw = new byte[32];
            random.nextBytes(raw);
            secret = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
            hash = sha256(secret);
            cfg = cfg.get("rateLimit") == null ? Map.of() : Map.of("rateLimit", cfg.get("rateLimit")); // the only setting a secret webhook keeps
        }
        Trigger t = new Trigger(p.tenantId(), id, kind, processId, true, cfg, null, hash, null, null, 0, p.userId(), clock.instant());
        store.create(t);
        return new Created(t, secret);
    }

    public List<Trigger> list(Principal p) {
        need(p, Permission.DEFINITION_READ);
        return store.list(p.tenantId());
    }

    public void setEnabled(Principal p, String id, boolean enabled) {
        need(p, Permission.DEFINITION_WRITE);
        Trigger t = store.get(p.tenantId(), id).orElseThrow(() -> ApiException.notFound("Trigger '" + id + "'"));
        // a schedule switched on starts counting from now: what came due while it was off is not run
        Instant lastAt = enabled && !t.enabled() && t.kind() == Trigger.Kind.SCHEDULE ? clock.instant() : t.lastPolledAt();
        store.update(t.with(enabled, t.cursor(), lastAt, enabled ? null : t.lastError(), enabled ? 0 : t.consecutiveFailures()));
    }

    public void delete(Principal p, String id) {
        need(p, Permission.DEFINITION_WRITE);
        if (!store.delete(p.tenantId(), id)) {
            throw ApiException.notFound("Trigger '" + id + "'");
        }
    }

    /** Suspended tenants' triggers neither fire nor poll. */
    public TriggerService withTenantGate(TenantGate g) {
        this.gate = g == null ? TenantGate.ALLOW_ALL : g;
        return this;
    }

    // ---------------------------------------------------------------- webhook

    public RunView fire(String tenant, String id, String providedSecret, Map<String, Object> payload) {
        Trigger t = store.get(tenant, id).orElse(null);
        // Compare against a dummy when the trigger does not exist, so timing does not tell the two cases apart.
        String expected = t == null || t.secretHash() == null ? sha256("no-such-trigger") : t.secretHash();
        boolean ok = providedSecret != null
                && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), sha256(providedSecret).getBytes(StandardCharsets.UTF_8));
        if (!ok || t == null || t.kind() != Trigger.Kind.WEBHOOK || !t.enabled() || usesProvider(t) || !gate.active(tenant)) {
            throw new ApiException(ApiException.Code.UNAUTHORIZED, "Invalid webhook");
        }
        Map<String, Object> event = payload == null ? Map.of() : payload;
        if (Json.write(event).length() > MAX_PAYLOAD_CHARS) {
            throw ApiException.badRequest("Payload larger than " + MAX_PAYLOAD_CHARS + " characters");
        }
        // A caller with the right secret is still held to the trigger's rateLimit (one allowance for the whole trigger).
        if (!withinRateLimit(t, Map.of())) {
            throw new ApiException(ApiException.Code.TOO_MANY_REQUESTS, "Too many calls to this webhook: try again later");
        }
        return api.startRun(runner(t), t.processId(), null, Map.of("event", event, "trigger", t.id()));
    }

    /** What to answer the caller of a webhook. */
    public record Reception(int status, String body, int runs, boolean json) {
    }

    /**
     * Handles a raw webhook request. {@code headers} may have any case. Triggers with {@code config.auth} naming a
     * provider are authenticated by that provider's signature over the raw body; others by {@code X-Wayang-Secret}.
     */
    public Reception receive(String tenant, String id, String method, Map<String, String> query, Map<String, String> headers, byte[] body) {
        Map<String, String> h = new java.util.HashMap<>();
        if (headers != null) {
            headers.forEach((k, v) -> h.put(k.toLowerCase(java.util.Locale.ROOT), v));
        }
        Trigger t = store.get(tenant, id).orElse(null);
        if (t == null || t.kind() != Trigger.Kind.WEBHOOK || !t.enabled() || !gate.active(tenant)) {
            // same answer and similar work as a bad secret
            sha256(h.getOrDefault("x-wayang-secret", ""));
            throw new ApiException(ApiException.Code.UNAUTHORIZED, "Invalid webhook");
        }
        byte[] raw = body == null ? new byte[0] : body;
        if (raw.length > MAX_BODY_BYTES) {
            throw ApiException.badRequest("Body larger than " + MAX_BODY_BYTES + " bytes");
        }
        if (!usesProvider(t)) {
            Map<String, Object> json = parseObject(raw);
            RunView run = fire(tenant, id, h.get("x-wayang-secret"), json);
            return new Reception(202, "{\"runId\":\"" + run.id() + "\"}", 1, true);
        }

        WebhookProvider provider = providers.get(String.valueOf(t.config().get("auth")));
        Set<String> used = new java.util.HashSet<>();
        Map<String, Object> cfg;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> resolved = (Map<String, Object>) resolve(t.tenant(), t.config(), used);
            cfg = resolved;
        } catch (RuntimeException e) {
            throw new ApiException(ApiException.Code.UNAVAILABLE, "Webhook is not configured");
        }
        try {
            boolean isGet = "GET".equalsIgnoreCase(method);
            if (isGet) {
                var challenge = provider.handshake(cfg, query == null ? Map.of() : query, null);
                if (challenge.isEmpty()) {
                    throw ApiException.badRequest("Not a verification request");
                }
                return new Reception(200, challenge.get(), 0, false);
            }
            provider.verify(cfg, h, raw, clock.instant());
            Map<String, Object> json = formOrJson(h, raw);
            var challenge = provider.handshake(cfg, query == null ? Map.of() : query, json);
            if (challenge.isPresent()) {
                return new Reception(200, challenge.get(), 0, false);
            }
            int started = 0;
            Principal runner = runner(t);
            for (WebhookProvider.Event ev : provider.events(cfg, json)) {
                if (seen(t, ev.id())) {
                    continue;
                }
                if (isFeedback(t, ev.data())) {
                    recordFeedback(t, ev.data()); // a thumbs press: feedback, never a message for the process
                    remember(t, ev.id());
                    continue;
                }
                boolean answer = answeredApproval(t, runner, ev.data()) || !withinRateLimit(t, ev.data());
                if (!answer) {
                    api.startRun(runner, t.processId(), null, Map.of("event", ev.data(), "trigger", t.id()));
                }
                remember(t, ev.id());
                started += answer ? 0 : 1;
            }
            return new Reception(200, "{\"runs\":" + started + "}", started, true);
        } catch (WebhookProvider.Rejected r) {
            throw new ApiException(ApiException.Code.UNAUTHORIZED, "Invalid webhook");
        }
    }

    private boolean usesProvider(Trigger t) {
        return t.config().get("auth") instanceof String a && !a.equals("secret");
    }

    /** Slack buttons arrive as a form with the JSON in {@code payload}; everything else is a JSON body. */
    private static Map<String, Object> formOrJson(Map<String, String> h, byte[] raw) {
        try {
            var form = tech.kayys.wayang.runtime.FormPayload.parse(h.get("content-type"), raw);
            if (form.isPresent()) {
                return form.get();
            }
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("payload must be a JSON object");
        }
        return parseObject(raw);
    }

    private static Map<String, Object> parseObject(byte[] raw) {
        if (raw.length == 0) {
            return Map.of();
        }
        try {
            Object v = Json.read(new String(raw, StandardCharsets.UTF_8));
            if (v instanceof Map<?, ?> m) {
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) m;
                return typed;
            }
        } catch (RuntimeException e) {
            // falls through
        }
        throw ApiException.badRequest("Body must be a JSON object");
    }

    private synchronized boolean seen(Trigger t, String eventId) {
        var set = recent.get(t.tenant() + "\u0000" + t.id());
        return set != null && set.contains(eventId);
    }

    private synchronized void remember(Trigger t, String eventId) {
        var set = recent.computeIfAbsent(t.tenant() + "\u0000" + t.id(), k -> new java.util.LinkedHashSet<>());
        set.add(eventId);
        while (set.size() > MAX_RECENT) {
            set.remove(set.iterator().next());
        }
    }

    private static void requireSecretRefs(Map<String, Object> cfg) {
        for (Map.Entry<String, Object> e : cfg.entrySet()) {
            if (SENSITIVE_KEY.matcher(e.getKey()).matches() && !(e.getValue() instanceof String v && PURE_SECRET_REF.matcher(v).matches())) {
                throw ApiException.badRequest("'" + e.getKey() + "' must be a secret reference like ${secret:NAME}, not a literal value");
            }
            if (e.getValue() instanceof Map<?, ?> nested) {
                @SuppressWarnings("unchecked")
                Map<String, Object> inner = (Map<String, Object>) nested;
                requireSecretRefs(inner);
            }
        }
    }

    // ------------------------------------------------------------ rate limit

    private final Map<String, tech.kayys.wayang.runtime.RateLimiter> limiters = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile tech.kayys.wayang.runtime.RateLimiter.Journal rateJournal;
    private volatile java.time.Instant journalPurgedAt = java.time.Instant.EPOCH;

    /** Keeps trigger rate-limit counts across restarts (see {@link tech.kayys.wayang.runtime.RateLimiter}); without it they live in memory only. */
    public TriggerService withRateJournal(tech.kayys.wayang.runtime.RateLimiter.Journal journal) {
        this.rateJournal = journal;
        return this;
    }

    /** At most hourly: the day window is the longest a trigger can ask for, so two days of history is more than enough. */
    private void purgeJournal() {
        var j = rateJournal;
        java.time.Instant now = clock.instant();
        if (j == null || journalPurgedAt.plus(java.time.Duration.ofHours(1)).isAfter(now)) {
            return;
        }
        journalPurgedAt = now;
        try {
            j.purgeBefore(now.minus(java.time.Duration.ofDays(2)));
        } catch (RuntimeException e) {
            // housekeeping only
        }
    }

    /**
     * Applies the trigger's {@code rateLimit} to one message: per conversation for chat triggers, otherwise per trigger. A message over the limit
     * is dropped (no run), and an answer to an approval never counts, so a person can always say yes or no.
     */
    private boolean withinRateLimit(Trigger t, Map<String, Object> ev) {
        if (t.config().get("rateLimit") == null) {
            return true;
        }
        var limiter = limiters.computeIfAbsent(t.tenant() + "\u0000" + t.id(), k -> {
            var l = tech.kayys.wayang.runtime.RateLimiter.fromTriggerConfig(t.config());
            return rateJournal == null ? l : l.withJournal(rateJournal, k);
        });
        String field = tech.kayys.wayang.runtime.ChatApproval.conversationField(t.config());
        Object who = field == null ? null : ev.get(field);
        return limiter.allow(who == null ? "*" : String.valueOf(who), clock.instant());
    }

    // --------------------------------------------------------------- approvals

    /** {@code approvals: true} is for chat triggers (Telegram, Slack): a plain yes or no from the chat answers a waiting approval. */
    public static void validateApprovals(Trigger.Kind kind, Map<String, Object> cfg) {
        Object a = cfg.get("approvals");
        if (a == null) {
            return;
        }
        if (!(a instanceof Boolean b)) {
            throw ApiException.badRequest("approvals must be true or false");
        }
        if (b && (kind == Trigger.Kind.SCHEDULE || tech.kayys.wayang.runtime.ChatApproval.conversationField(cfg) == null)) {
            throw ApiException.badRequest("approvals is for Telegram and Slack triggers only");
        }
    }

    /** {@code ratings: true} is for Telegram poll and Slack webhook triggers: thumbs presses on the bot's replies are recorded as feedback. */
    public static void validateRatings(Trigger.Kind kind, Map<String, Object> cfg) {
        Object r = cfg.get("ratings");
        if (r == null) {
            return;
        }
        if (!(r instanceof Boolean b)) {
            throw ApiException.badRequest("ratings must be true or false");
        }
        boolean telegram = kind == Trigger.Kind.POLL && "telegram".equals(cfg.get("source"));
        boolean slack = kind == Trigger.Kind.WEBHOOK && "slack".equals(cfg.get("auth"));
        if (b && !telegram && !slack) {
            throw ApiException.badRequest("ratings is for Telegram poll and Slack webhook triggers only");
        }
    }

    /**
     * A "yes" or "no" from a chat answers the oldest approval this trigger's runs wait on in that same conversation.
     *
     * @return true when the message was an answer and is therefore not a new message for the process
     */
    private boolean answeredApproval(Trigger t, Principal runner, Map<String, Object> ev) {
        if (!Boolean.TRUE.equals(t.config().get("approvals"))) {
            return false;
        }
        String field = tech.kayys.wayang.runtime.ChatApproval.conversationField(t.config());
        var decision = tech.kayys.wayang.runtime.ChatApproval.decision(ev.get("text"));
        if (field == null || decision.isEmpty()) {
            return false;
        }
        RunView oldest = null;
        TaskView task = null;
        for (RunView r : api.listRuns(runner, t.processId(), 200)) {
            if (r.status() != RunStatus.WAITING || !tech.kayys.wayang.runtime.ChatApproval.sameConversation(r.variables(), t.id(), field, ev.get(field))) {
                continue;
            }
            for (TaskView w : r.waiting()) {
                if ("human".equals(w.kind()) && (oldest == null || r.startedAt().isBefore(oldest.startedAt()))) {
                    oldest = r;
                    task = w;
                }
            }
        }
        if (task == null) {
            return false;
        }
        try {
            api.completeTask(runner, task.token(), Map.of("approved", decision.get()));
            return true;
        } catch (ApiException e) {
            return false; // answered meanwhile (a second device, the console): an ordinary message
        }
    }

    // ---------------------------------------------------------------- schedule

    private static final java.util.Set<String> SCHEDULE_KEYS = java.util.Set.of("cron", "timezone", "graceSeconds", "input");
    private static final long DEFAULT_GRACE_SECONDS = 3600;

    /**
     * Checks a SCHEDULE configuration: {@code cron} (five fields), optional {@code timezone} (IANA id, default UTC), {@code graceSeconds}
     * (0..86400, default 3600: a run missed by more than this is skipped, not run late) and {@code input} (an object, up to 8 KB, merged into the
     * event).
     *
     * @throws ApiException 400 with the reason
     */
    public static void validateSchedule(Map<String, Object> cfg) {
        for (String k : cfg.keySet()) {
            if (!SCHEDULE_KEYS.contains(k)) {
                throw ApiException.badRequest("A schedule takes " + new java.util.TreeSet<>(SCHEDULE_KEYS) + ", not '" + k + "'");
            }
        }
        try {
            tech.kayys.wayang.runtime.CronSchedule.parse(cfg.get("cron") instanceof String c ? c : null,
                    cfg.get("timezone") instanceof String z ? z : null);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        }
        if (cfg.get("timezone") != null && !(cfg.get("timezone") instanceof String)) {
            throw ApiException.badRequest("timezone must be text such as Asia/Jakarta");
        }
        if (cfg.get("graceSeconds") != null && !(cfg.get("graceSeconds") instanceof Number n && n.longValue() >= 0 && n.longValue() <= 86_400)) {
            throw ApiException.badRequest("graceSeconds must be between 0 and 86400");
        }
        if (cfg.get("input") != null) {
            if (!(cfg.get("input") instanceof Map<?, ?> in) || tech.kayys.wayang.runtime.json.Json.write(in).length() > 8 * 1024) {
                throw ApiException.badRequest("input must be an object of at most 8 KB");
            }
        }
    }

    /** Runs a schedule that is due, once. Returns how many runs it started (0 or 1). */
    private int fireSchedule(Trigger t, Instant now) {
        try {
            var cs = tech.kayys.wayang.runtime.CronSchedule.parse((String) t.config().get("cron"), (String) t.config().get("timezone"));
            Instant base = t.lastPolledAt() != null ? t.lastPolledAt() : t.createdAt();
            Instant due = cs.next(base);
            if (due == null || due.isAfter(now)) {
                return 0;
            }
            long grace = t.config().get("graceSeconds") instanceof Number n ? n.longValue() : DEFAULT_GRACE_SECONDS;
            if (now.isAfter(due.plusSeconds(grace))) {
                // the server was down or the trigger off at that time: a morning brief at 3 pm helps nobody. Wait for the next one.
                store.update(t.with(true, t.cursor(), now, "Skipped the run scheduled for " + due + ": it was more than " + grace
                        + " s late. The next one runs on time.", 0));
                return 0;
            }
            Map<String, Object> ev = new java.util.LinkedHashMap<>();
            if (t.config().get("input") instanceof Map<?, ?> in) {
                in.forEach((k, v) -> ev.put(String.valueOf(k), v));
            }
            ev.put("scheduledFor", due.toString());
            ev.put("firedAt", now.toString());
            ev.put("timezone", cs.zone().getId());
            api.startRun(runner(t), t.processId(), null, Map.of("event", ev, "trigger", t.id()));
            store.update(t.with(true, t.cursor(), now, null, 0));
            return 1;
        } catch (Exception e) {
            // lastPolledAt stays as it was, so the next tick tries again until the grace period ends
            String msg = String.valueOf(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            store.update(t.with(true, t.cursor(), t.lastPolledAt(), msg.length() > 500 ? msg.substring(0, 500) : msg, t.consecutiveFailures() + 1));
            return 0;
        }
    }

    // ------------------------------------------------------------------- poll

    /** Polls every trigger that is due. Returns how many runs were started. Never throws for one trigger's failure. */
    public synchronized int tick() {
        int started = 0;
        Instant now = clock.instant();
        purgeJournal();
        for (Trigger t : store.pollable()) {
            if (!gate.active(t.tenant())) {
                continue;
            }
            if (t.kind() == Trigger.Kind.SCHEDULE) {
                started += fireSchedule(t, now);
            } else if (due(t, now)) {
                started += pollOne(t, now);
            }
        }
        return started;
    }

    private boolean due(Trigger t, Instant now) {
        if (t.lastPolledAt() == null) {
            return true;
        }
        long every = t.config().get("everySeconds") instanceof Number n ? n.longValue() : 30;
        Duration wait = Duration.ofSeconds(every);
        for (int i = 0; i < Math.min(t.consecutiveFailures(), 10); i++) {
            wait = wait.multipliedBy(2);
            if (wait.compareTo(MAX_BACKOFF) >= 0) {
                wait = MAX_BACKOFF;
                break;
            }
        }
        return !t.lastPolledAt().plus(wait).isAfter(now);
    }

    private int pollOne(Trigger t, Instant now) {
        Set<String> resolvedValues = new java.util.HashSet<>();
        try {
            Poller poller = pollers.get(String.valueOf(t.config().get("source")));
            if (poller == null) {
                throw new IllegalStateException("poller is not available");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> cfg = (Map<String, Object>) resolve(t.tenant(), t.config(), resolvedValues);
            Poller.Result res = poller.poll(cfg, t.cursor());
            Principal runner = runner(t);
            int n = 0;
            for (Map<String, Object> ev : res.events()) {
                if (isFeedback(t, ev)) {
                    recordFeedback(t, ev); // a thumbs press on a reply is feedback, never a message for the process
                    continue;
                }
                if (!answeredApproval(t, runner, ev) && withinRateLimit(t, ev)) {
                    api.startRun(runner, t.processId(), null, Map.of("event", ev, "trigger", t.id()));
                    n++;
                }
            }
            store.update(t.with(true, res.nextCursor() != null ? res.nextCursor() : t.cursor(), now, null, 0));
            return n;
        } catch (Exception e) {
            String msg = redact(String.valueOf(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()), resolvedValues);
            store.update(t.with(true, t.cursor(), now, msg.length() > 500 ? msg.substring(0, 500) : msg, t.consecutiveFailures() + 1));
            return 0;
        }
    }

    /** Where thumbs presses go (set by the API wiring); without it they are dropped. */
    private volatile tech.kayys.wayang.api.feedback.FeedbackService feedback;

    public TriggerService withFeedback(tech.kayys.wayang.api.feedback.FeedbackService service) {
        this.feedback = service;
        return this;
    }

    /** Only the Telegram poller produces these, and only when the trigger asked for {@code ratings}. */
    private static boolean isFeedback(Trigger t, Map<String, Object> ev) {
        return chatSource(t) != null && Boolean.TRUE.equals(t.config().get("ratings")) && "feedback".equals(ev.get("kind")) && ev.get("rating") instanceof String;
    }

    /** {@code telegram} or {@code slack} for the triggers that can carry ratings, else null. */
    private static String chatSource(Trigger t) {
        if (t.kind() == Trigger.Kind.POLL && "telegram".equals(t.config().get("source"))) {
            return "telegram";
        }
        return t.kind() == Trigger.Kind.WEBHOOK && "slack".equals(t.config().get("auth")) ? "slack" : null;
    }

    private void recordFeedback(Trigger t, Map<String, Object> ev) {
        var svc = feedback;
        if (svc == null) {
            return;
        }
        Map<String, Object> ctx = new LinkedHashMap<>();
        String source = chatSource(t);
        ctx.put("source", source);
        ctx.put("trigger", t.id());
        ctx.put("processId", t.processId());
        for (String k : new String[] { "chatId", "messageId", "channel", "messageTs" }) {
            if (ev.get(k) != null) {
                ctx.put(k, String.valueOf(ev.get(k)));
            }
        }
        if (ev.get("reply") instanceof String r && !r.isBlank()) {
            ctx.put("reply", r);
        }
        try {
            String from = String.valueOf(ev.get("fromId") != null ? ev.get("fromId") : ev.get("user")).replaceAll("[^A-Za-z0-9_-]", "");
            svc.submit(new Principal(t.tenant(), source + "-" + (from.isEmpty() || from.equals("null") ? "unknown" : from), Set.of()),
                    Map.of("kind", "rating", "rating", ev.get("rating"), "context", ctx));
        } catch (RuntimeException e) {
            // over the feedback rate limit or invalid: a lost thumb must never fail the poll
        }
    }

    // ---------------------------------------------------------------- helpers

    private static Principal runner(Trigger t) {
        return new Principal(t.tenant(), "trigger-" + t.id(), Set.of(), EnumSet.of(Permission.RUN_START, Permission.RUN_READ, Permission.TASK_COMPLETE));
    }

    private Object resolve(String tenant, Object v, Set<String> used) {
        if (v instanceof String s) {
            Matcher m = SECRET_REF.matcher(s);
            StringBuilder out = new StringBuilder();
            while (m.find()) {
                String value = secrets == null ? null : secrets.resolve(tenant, m.group(1));
                if (value == null) {
                    throw new IllegalStateException("Secret '" + m.group(1) + "' is not set");
                }
                used.add(value);
                m.appendReplacement(out, Matcher.quoteReplacement(value));
            }
            m.appendTail(out);
            return out.toString();
        }
        if (v instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, x) -> out.put(String.valueOf(k), resolve(tenant, x, used)));
            return out;
        }
        if (v instanceof List<?> l) {
            return l.stream().map(x -> resolve(tenant, x, used)).toList();
        }
        return v;
    }

    private static String redact(String message, Set<String> secretValues) {
        String out = message;
        for (String s : secretValues) {
            if (s.length() >= 4) {
                out = out.replace(s, "***");
            }
        }
        return out;
    }

    private static void need(Principal p, Permission perm) {
        if (p == null) {
            throw new ApiException(ApiException.Code.UNAUTHORIZED, "Not authenticated");
        }
        if (!p.can(perm)) {
            throw ApiException.forbidden(perm);
        }
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
