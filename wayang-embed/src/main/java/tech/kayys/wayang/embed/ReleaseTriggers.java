package tech.kayys.wayang.embed;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tech.kayys.wayang.runtime.Poller;
import tech.kayys.wayang.runtime.SecretResolver;
import tech.kayys.wayang.runtime.WebhookProvider;
import tech.kayys.wayang.runtime.json.Json;

/**
 * The triggers of a release: webhook routes ({@code /hooks/<id>}) and poll loops, a lean counterpart of the design-time trigger
 * service with the same rules. A webhook is authenticated by the shared secret in {@code X-Wayang-Secret} (supplied by the
 * environment as secret {@code trigger.<id>}) or, when {@code config.auth} names a provider, by that provider's signature.
 * Provider events are de-duplicated by event id (last 5,000 per trigger). A poll loop is at-least-once: the cursor moves only
 * after every event of the batch started a run; failures back off exponentially up to one hour. The run input is
 * {@code {event, trigger}}. Cursors live in memory, or in {@code stateDir} when given, so a restart does not re-read old events.
 * Everything fails closed: a missing secret or a bad signature answers the same 401 as an unknown trigger.
 */
public final class ReleaseTriggers implements AutoCloseable {

    /** What to answer the caller of a webhook. */
    public record Reception(int status, String body, boolean json) {
    }

    /** A webhook the caller must not see the reason for, or a bad request. */
    public static final class Refused extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final int status;

        Refused(int status, String message) {
            super(message, null, false, false);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    private static final Pattern SECRET_REF = Pattern.compile("\\$\\{secret:([A-Za-z0-9_.-]{1,100})}");
    private static final int MAX_BODY = 1 << 20;
    private static final int MAX_PAYLOAD_CHARS = 64 * 1024;
    private static final int MAX_RECENT = 5000;
    private static final Duration MAX_BACKOFF = Duration.ofHours(1);

    private static final class PollState {
        String cursor;
        Instant lastPolledAt;
        String lastError;
        int failures;
    }

    private static final class Schedule {
        tech.kayys.wayang.runtime.CronSchedule cron;
        long graceSeconds;
        Instant last; // when it last ran (or was skipped); what the next run is counted from
        String lastError;
        int failures;
    }

    private final Map<String, Schedule> schedules = new HashMap<>();
    private final Map<String, ReleaseBundle.Trigger> byId = new LinkedHashMap<>();
    private final Map<String, PollState> polls = new HashMap<>();
    private final Map<String, LinkedHashSet<String>> recent = new HashMap<>();
    private final ReleaseEngine rt;
    private final SecretResolver secrets;
    private final Map<String, Poller> pollers;
    private final Map<String, WebhookProvider> providers;
    private final Clock clock;
    private final Path stateDir;
    private ScheduledExecutorService ticker;

    /**
     * @param stateDir where poll cursors are kept between restarts; null keeps them in memory only
     * @throws IllegalArgumentException when a trigger needs a poller or provider that was not supplied, or has a bad schedule
     */
    public ReleaseTriggers(ReleaseBundle bundle, ReleaseEngine rt, SecretResolver secrets, Map<String, Poller> pollers,
            Map<String, WebhookProvider> providers, Clock clock, Path stateDir) {
        this.rt = rt;
        this.secrets = secrets;
        this.pollers = pollers == null ? Map.of() : Map.copyOf(pollers);
        this.providers = providers == null ? Map.of() : Map.copyOf(providers);
        this.clock = clock;
        this.stateDir = stateDir;
        for (ReleaseBundle.Trigger t : bundle.triggers()) {
            if (t.schedule()) {
                Schedule sc = new Schedule();
                sc.cron = tech.kayys.wayang.runtime.CronSchedule.parse((String) t.config().get("cron"), (String) t.config().get("timezone"));
                sc.graceSeconds = t.config().get("graceSeconds") instanceof Number n ? n.longValue() : 3600;
                String saved = loadCursor(t.id());
                Instant last = null;
                try {
                    last = saved == null ? null : Instant.ofEpochMilli(Long.parseLong(saved.strip()));
                } catch (NumberFormatException e) {
                    // a damaged state file counts from now, like a first start
                }
                sc.last = last != null ? last : clock.instant(); // a first start does not run what was due before it existed
                schedules.put(t.id(), sc);
            } else if (t.webhook()) {
                if (t.provider() != null && !this.providers.containsKey(t.provider())) {
                    throw new IllegalArgumentException("Trigger " + t.id() + " needs the webhook provider '" + t.provider() + "', which is not available");
                }
            } else {
                if (!(t.config().get("source") instanceof String s) || !this.pollers.containsKey(s)) {
                    throw new IllegalArgumentException("Trigger " + t.id() + " needs a poll source that is not available");
                }
                if (t.config().get("everySeconds") != null
                        && !(t.config().get("everySeconds") instanceof Number n && n.longValue() >= 5 && n.longValue() <= 86_400)) {
                    throw new IllegalArgumentException("Trigger " + t.id() + ": everySeconds must be between 5 and 86400");
                }
                PollState st = new PollState();
                st.cursor = loadCursor(t.id());
                polls.put(t.id(), st);
            }
            byId.put(t.id(), t);
        }
    }

    public boolean isEmpty() {
        return byId.isEmpty();
    }

    /** What each trigger is doing: no configuration, no secrets. */
    public synchronized List<Map<String, Object>> status() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ReleaseBundle.Trigger t : byId.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.id());
            m.put("kind", t.kind());
            m.put("processId", t.processId());
            Schedule sc = schedules.get(t.id());
            if (sc != null) {
                Instant next = sc.cron.next(sc.last);
                m.put("lastRunAt", sc.last == null ? null : sc.last.toString());
                m.put("nextRunAt", next == null ? null : next.toString());
                m.put("consecutiveFailures", sc.failures);
                m.put("lastError", sc.lastError);
            }
            PollState s = polls.get(t.id());
            if (s != null) {
                m.put("lastPolledAt", s.lastPolledAt == null ? null : s.lastPolledAt.toString());
                m.put("consecutiveFailures", s.failures);
                m.put("lastError", s.lastError);
            }
            out.add(m);
        }
        return out;
    }

    // ---------------------------------------------------------------- webhook

    /** @param headers any case; @throws Refused 401 for anything not authenticated, 400/413/429 for the caller's own mistakes */
    public Reception receive(String id, String method, Map<String, String> query, Map<String, String> headers, byte[] body) {
        Map<String, String> h = new HashMap<>();
        if (headers != null) {
            headers.forEach((k, v) -> h.put(k.toLowerCase(Locale.ROOT), v));
        }
        ReleaseBundle.Trigger t = byId.get(id);
        if (t == null || !t.webhook()) {
            sha256(h.getOrDefault("x-wayang-secret", "")); // similar work as a bad secret
            throw new Refused(401, "Invalid webhook");
        }
        byte[] raw = body == null ? new byte[0] : body;
        if (raw.length > MAX_BODY) {
            throw new Refused(413, "Body larger than " + MAX_BODY + " bytes");
        }
        if (t.provider() == null) {
            String expected = secrets == null ? null : secrets.resolve(ReleaseEngine.TENANT, t.secretName());
            String given = h.get("x-wayang-secret");
            // always compare, against a dummy when nothing is configured, so timing does not tell the cases apart
            boolean ok = expected != null && !expected.isEmpty() && given != null
                    && MessageDigest.isEqual(sha256(expected), sha256(given));
            if (!ok) {
                if (expected == null) {
                    sha256(given == null ? "" : given);
                }
                throw new Refused(401, "Invalid webhook");
            }
            Map<String, Object> json = parseObject(raw);
            return new Reception(202, Json.write(Map.of("runId", start(t, json).id())), true);
        }

        WebhookProvider provider = providers.get(t.provider());
        Map<String, Object> cfg;
        try {
            cfg = resolve(t.config(), new HashSet<>());
        } catch (RuntimeException e) {
            throw new Refused(503, "Webhook is not configured");
        }
        try {
            if ("GET".equalsIgnoreCase(method)) {
                var challenge = provider.handshake(cfg, query == null ? Map.of() : query, null);
                if (challenge.isEmpty()) {
                    throw new Refused(400, "Not a verification request");
                }
                return new Reception(200, challenge.get(), false);
            }
            provider.verify(cfg, h, raw, clock.instant());
            Map<String, Object> json = parseObject(raw);
            var challenge = provider.handshake(cfg, query == null ? Map.of() : query, json);
            if (challenge.isPresent()) {
                return new Reception(200, challenge.get(), false);
            }
            int started = 0;
            for (WebhookProvider.Event ev : provider.events(cfg, json)) {
                if (seen(t, ev.id())) {
                    continue;
                }
                boolean answer = answeredApproval(t, ev.data()) || !withinRateLimit(t, ev.data());
                if (!answer) {
                    start(t, ev.data());
                }
                remember(t, ev.id());
                started += answer ? 0 : 1;
            }
            return new Reception(200, "{\"runs\":" + started + "}", true);
        } catch (WebhookProvider.Rejected r) {
            throw new Refused(401, "Invalid webhook");
        }
    }

    private ReleaseEngine.RunInfo start(ReleaseBundle.Trigger t, Map<String, Object> event) {
        if (Json.write(event).length() > MAX_PAYLOAD_CHARS) {
            throw new Refused(400, "Payload larger than " + MAX_PAYLOAD_CHARS + " characters");
        }
        try {
            return rt.start(t.processId(), Map.of("event", event, "trigger", t.id()));
        } catch (ReleaseEngine.Unavailable e) {
            throw new Refused(503, e.getMessage());
        } catch (IllegalStateException e) {
            throw new Refused(429, "Too many parked runs");
        }
    }

    /** A "yes" or "no" from the chat answers the oldest approval this trigger's runs wait on there; see {@link tech.kayys.wayang.runtime.ChatApproval}. */
    private boolean answeredApproval(ReleaseBundle.Trigger t, Map<String, Object> ev) {
        if (!Boolean.TRUE.equals(t.config().get("approvals"))) {
            return false;
        }
        String field = tech.kayys.wayang.runtime.ChatApproval.conversationField(t.config());
        var decision = tech.kayys.wayang.runtime.ChatApproval.decision(ev.get("text"));
        if (field == null || decision.isEmpty()) {
            return false;
        }
        ReleaseEngine.RunInfo oldest = null;
        String token = null;
        for (ReleaseEngine.RunInfo r : rt.list(t.processId(), 200)) {
            if (!"WAITING".equals(r.status()) || !tech.kayys.wayang.runtime.ChatApproval.sameConversation(r.variables(), t.id(), field, ev.get(field))) {
                continue;
            }
            for (Map<String, Object> w : r.waiting()) {
                if ("human".equals(w.get("kind")) && (oldest == null || r.startedAt().isBefore(oldest.startedAt()))) {
                    oldest = r;
                    token = String.valueOf(w.get("token"));
                }
            }
        }
        return token != null && rt.completeWait(token, Map.of("approved", decision.get()));
    }

    private final Map<String, tech.kayys.wayang.runtime.RateLimiter> limiters = new HashMap<>();

    /** The trigger's {@code rateLimit}, per conversation for chat triggers; a message over it is dropped. See the server's TriggerService. */
    private synchronized boolean withinRateLimit(ReleaseBundle.Trigger t, Map<String, Object> ev) {
        if (t.config().get("rateLimit") == null) {
            return true;
        }
        var limiter = limiters.computeIfAbsent(t.id(), k -> tech.kayys.wayang.runtime.RateLimiter.fromTriggerConfig(t.config()));
        String field = tech.kayys.wayang.runtime.ChatApproval.conversationField(t.config());
        Object who = field == null ? null : ev.get(field);
        return limiter.allow(who == null ? "*" : String.valueOf(who), clock.instant());
    }

    private static Map<String, Object> parseObject(byte[] raw) {
        if (raw.length == 0) {
            return Map.of();
        }
        try {
            if (Json.read(new String(raw, StandardCharsets.UTF_8)) instanceof Map<?, ?> m) {
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) m;
                return typed;
            }
        } catch (RuntimeException e) {
            // falls through
        }
        throw new Refused(400, "Body must be a JSON object");
    }

    private synchronized boolean seen(ReleaseBundle.Trigger t, String eventId) {
        var set = recent.get(t.id());
        return set != null && set.contains(eventId);
    }

    private synchronized void remember(ReleaseBundle.Trigger t, String eventId) {
        var set = recent.computeIfAbsent(t.id(), k -> new LinkedHashSet<>());
        set.add(eventId);
        while (set.size() > MAX_RECENT) {
            set.remove(set.iterator().next());
        }
    }

    // ------------------------------------------------------------------- poll

    /** Starts the poll loop (one thread, checks every second which triggers are due). */
    public synchronized ReleaseTriggers start() {
        if (ticker == null && (!polls.isEmpty() || !schedules.isEmpty())) {
            ticker = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread th = new Thread(r, "wayang-pollers");
                th.setDaemon(true);
                return th;
            });
            ticker.scheduleWithFixedDelay(() -> {
                try {
                    tick();
                } catch (RuntimeException e) {
                    // one failing round must not stop the loop
                }
            }, 1, 1, TimeUnit.SECONDS);
        }
        return this;
    }

    @Override
    public synchronized void close() {
        if (ticker != null) {
            ticker.shutdownNow();
            ticker = null;
        }
    }

    /** Polls every trigger that is due; returns how many runs were started. Never throws for one trigger's failure. */
    public synchronized int tick() {
        int started = 0;
        Instant now = clock.instant();
        for (Map.Entry<String, PollState> e : polls.entrySet()) {
            ReleaseBundle.Trigger t = byId.get(e.getKey());
            if (due(t, e.getValue(), now)) {
                started += pollOne(t, e.getValue(), now);
            }
        }
        for (Map.Entry<String, Schedule> e : schedules.entrySet()) {
            started += fireSchedule(byId.get(e.getKey()), e.getValue(), now);
        }
        return started;
    }

    /** Runs a schedule that is due, once; a run missed by more than {@code graceSeconds} is skipped. Returns 0 or 1. */
    private int fireSchedule(ReleaseBundle.Trigger t, Schedule sc, Instant now) {
        Instant due = sc.cron.next(sc.last);
        if (due == null || due.isAfter(now)) {
            return 0;
        }
        if (now.isAfter(due.plusSeconds(sc.graceSeconds))) {
            sc.last = now;
            sc.lastError = "Skipped the run scheduled for " + due + ": it was more than " + sc.graceSeconds + " s late. The next one runs on time.";
            saveCursor(t.id(), Long.toString(now.toEpochMilli()));
            return 0;
        }
        Map<String, Object> ev = new LinkedHashMap<>();
        if (t.config().get("input") instanceof Map<?, ?> in) {
            in.forEach((k, v) -> ev.put(String.valueOf(k), v));
        }
        ev.put("scheduledFor", due.toString());
        ev.put("firedAt", now.toString());
        ev.put("timezone", sc.cron.zone().getId());
        try {
            start(t, ev);
            sc.last = now;
            sc.lastError = null;
            sc.failures = 0;
            saveCursor(t.id(), Long.toString(now.toEpochMilli()));
            return 1;
        } catch (RuntimeException e) {
            // sc.last stays, so the next tick tries again until the grace period ends
            String msg = e instanceof Refused r ? "Could not start the run (" + r.status() + ")" : "Could not start the run";
            sc.lastError = msg;
            sc.failures++;
            return 0;
        }
    }

    private static boolean due(ReleaseBundle.Trigger t, PollState s, Instant now) {
        if (s.lastPolledAt == null) {
            return true;
        }
        long every = t.config().get("everySeconds") instanceof Number n ? n.longValue() : 30;
        Duration wait = Duration.ofSeconds(every);
        for (int i = 0; i < Math.min(s.failures, 10); i++) {
            wait = wait.multipliedBy(2);
            if (wait.compareTo(MAX_BACKOFF) >= 0) {
                wait = MAX_BACKOFF;
                break;
            }
        }
        return !s.lastPolledAt.plus(wait).isAfter(now);
    }

    private int pollOne(ReleaseBundle.Trigger t, PollState s, Instant now) {
        Set<String> used = new HashSet<>();
        try {
            Poller.Result res = pollers.get(String.valueOf(t.config().get("source"))).poll(resolve(t.config(), used), s.cursor);
            int n = 0;
            for (Map<String, Object> ev : res.events()) {
                if (!answeredApproval(t, ev) && withinRateLimit(t, ev)) {
                    start(t, ev);
                    n++;
                }
            }
            if (res.nextCursor() != null) {
                s.cursor = res.nextCursor();
                saveCursor(t.id(), s.cursor);
            }
            s.lastPolledAt = now;
            s.lastError = null;
            s.failures = 0;
            return n;
        } catch (Exception e) {
            String msg = redact(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), used);
            s.lastPolledAt = now;
            s.lastError = msg.length() > 500 ? msg.substring(0, 500) : msg;
            s.failures++;
            return 0;
        }
    }

    // ---------------------------------------------------------------- cursors

    private String loadCursor(String id) {
        if (stateDir == null) {
            return null;
        }
        try {
            Path f = stateDir.resolve("cursor-" + id);
            return Files.isRegularFile(f) && Files.size(f) <= 64 * 1024 ? Files.readString(f, StandardCharsets.UTF_8) : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private void saveCursor(String id, String cursor) {
        if (stateDir == null) {
            return;
        }
        try {
            Files.createDirectories(stateDir);
            Path tmp = Files.createTempFile(stateDir, "cursor-", ".tmp");
            Files.writeString(tmp, cursor, StandardCharsets.UTF_8);
            Files.move(tmp, stateDir.resolve("cursor-" + id), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            // best effort: the worst case is re-reading events, which the at-least-once contract already allows
        }
    }

    // ---------------------------------------------------------------- helpers

    @SuppressWarnings("unchecked")
    private Map<String, Object> resolve(Map<String, Object> config, Set<String> used) {
        return (Map<String, Object>) resolveValue(config, used);
    }

    private Object resolveValue(Object v, Set<String> used) {
        if (v instanceof String s) {
            Matcher m = SECRET_REF.matcher(s);
            StringBuilder out = new StringBuilder();
            while (m.find()) {
                String value = secrets == null ? null : secrets.resolve(ReleaseEngine.TENANT, m.group(1));
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
            map.forEach((k, x) -> out.put(String.valueOf(k), resolveValue(x, used)));
            return out;
        }
        if (v instanceof List<?> l) {
            return l.stream().map(x -> resolveValue(x, used)).toList();
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

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
