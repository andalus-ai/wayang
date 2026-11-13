package tech.kayys.wayang.api.feedback;

import tech.kayys.wayang.api.ApiException;
import tech.kayys.wayang.api.Permission;
import tech.kayys.wayang.api.Principal;
import tech.kayys.wayang.api.RateLimiter;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Collects feedback from signed-in users: ratings, comments, bug reports, ideas and error reports. Anyone authenticated may send it
 * (it only ever lands in their own tenant's log); reading it needs tenant-wide ADMIN. Everything stored passes through {@link #scrub}
 * first, because error reports and pasted text are where credentials leak, and the size of every field is capped.
 */
public final class FeedbackService {

    public static final Set<String> KINDS = Set.of("rating", "comment", "bug", "idea", "error");
    public static final int MAX_MESSAGE = 4000;
    public static final int MAX_STACK = 8000;
    public static final int MAX_VALUE = 500;
    public static final int MAX_CONTEXT = 12;
    public static final int MAX_LIST = 500;
    private static final Pattern KEY = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]{0,39}");

    private static final Pattern AUTH = Pattern.compile("(?i)\\b(authorization|bearer|basic)(\\s*[:=]?\\s*)[A-Za-z0-9._~+/=-]{8,}");
    private static final Pattern NAMED = Pattern.compile(
            "(?i)\\b(api[_-]?key|access[_-]?token|refresh[_-]?token|client[_-]?secret|token|secret|password|passwd|pwd)\\b([\"']?\\s*[:=]\\s*[\"']?)[^\\s\"',;&}]+");
    private static final Pattern USERINFO = Pattern.compile("(?i)(https?://)[^\\s/@]+@");
    private static final Pattern URL_QUERY = Pattern.compile("(?i)(https?://[^\\s?\"'#]+)\\?[^\\s\"'#]*");
    private static final Pattern JWT = Pattern.compile("\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]*");
    private static final Pattern KNOWN_TOKENS = Pattern.compile(
            "\\b(sk-[A-Za-z0-9_-]{16,}|xox[abprs]-[A-Za-z0-9-]{10,}|gh[pousr]_[A-Za-z0-9]{20,}|\\d{6,}:[A-Za-z0-9_-]{30,}|[A-Za-z0-9_-]{40,})\\b");

    private final FeedbackStore store;
    private final Clock clock;
    private final RateLimiter limiter;

    /** @param limiter shared across requests (the REST layer keeps one); null for no limit */
    public FeedbackService(FeedbackStore store, Clock clock, RateLimiter limiter) {
        this.store = store;
        this.clock = clock;
        this.limiter = limiter;
    }

    /**
     * @param req {@code {kind, rating?, message?, context?: {name: text}}}; a {@code rating} needs {@code rating} up/down, everything else needs a message
     * @return {@code {id}}
     */
    public Map<String, Object> submit(Principal p, Map<String, Object> req) {
        if (p == null) {
            throw new ApiException(ApiException.Code.UNAUTHORIZED, "Not authenticated");
        }
        if (req == null || !(req.get("kind") instanceof String kind) || !KINDS.contains(kind)) {
            throw ApiException.badRequest("kind must be one of " + new java.util.TreeSet<>(KINDS));
        }
        String rating = null;
        if (req.get("rating") != null) {
            if (!(req.get("rating") instanceof String r) || !(r.equals("up") || r.equals("down"))) {
                throw ApiException.badRequest("rating must be \"up\" or \"down\"");
            }
            rating = r;
        }
        if ("rating".equals(kind) && rating == null) {
            throw ApiException.badRequest("a rating needs rating: \"up\" or \"down\"");
        }
        String message = "";
        if (req.get("message") != null) {
            if (!(req.get("message") instanceof String m)) {
                throw ApiException.badRequest("message must be text");
            }
            if (m.length() > MAX_MESSAGE) {
                throw ApiException.badRequest("message is longer than " + MAX_MESSAGE + " characters");
            }
            message = scrub(m).strip();
        }
        if (!"rating".equals(kind) && message.isEmpty()) {
            throw ApiException.badRequest("message is required");
        }
        Map<String, String> context = new LinkedHashMap<>();
        if (req.get("context") != null) {
            if (!(req.get("context") instanceof Map<?, ?> c) || c.size() > MAX_CONTEXT) {
                throw ApiException.badRequest("context must be an object with at most " + MAX_CONTEXT + " entries");
            }
            for (var e : c.entrySet()) {
                if (!(e.getKey() instanceof String k) || !KEY.matcher(k).matches() || !(e.getValue() instanceof String || e.getValue() instanceof Number
                        || e.getValue() instanceof Boolean)) {
                    throw ApiException.badRequest("context entries need a short name and a text, number or boolean value");
                }
                String v = String.valueOf(e.getValue());
                int max = "stack".equals(k) ? MAX_STACK : MAX_VALUE;
                if (v.length() > max) {
                    throw ApiException.badRequest("context." + k + " is longer than " + max + " characters");
                }
                context.put(k, scrub(v));
            }
        }
        if (limiter != null) {
            limiter.check(p);
        }
        String id = UUID.randomUUID().toString();
        store.append(new FeedbackEntry(id, p.tenantId(), Instant.now(clock), p.userId(), kind, rating, message, context));
        return Map.of("id", id);
    }

    /** Newest first; tenant-wide ADMIN only. */
    public List<FeedbackEntry> list(Principal p, String kind, int limit, Instant before) {
        if (p == null) {
            throw new ApiException(ApiException.Code.UNAUTHORIZED, "Not authenticated");
        }
        if (!p.can(Permission.ADMIN)) {
            throw ApiException.forbidden(Permission.ADMIN);
        }
        if (kind != null && !KINDS.contains(kind)) {
            throw ApiException.badRequest("kind must be one of " + new java.util.TreeSet<>(KINDS));
        }
        return store.list(p.tenantId(), kind, Math.max(1, Math.min(limit, MAX_LIST)), before);
    }

    /**
     * What is waiting: counts over the newest {@value #MAX_LIST} entries of the tenant (ADMIN only): {@code open} (all kinds), {@code byKind}
     * (open ones per kind) and the thumbs {@code up}/{@code down}. {@code sampled} is true when there may be older entries not counted.
     */
    public Map<String, Object> summary(Principal p) {
        List<FeedbackEntry> rows = list(p, null, MAX_LIST, null);
        Map<String, Object> byKind = new java.util.TreeMap<>();
        long open = 0, up = 0, down = 0;
        for (FeedbackEntry e : rows) {
            if ("rating".equals(e.kind())) {
                if ("up".equals(e.rating())) {
                    up++;
                } else if ("down".equals(e.rating())) {
                    down++;
                }
            }
            if (FeedbackEntry.OPEN.equals(e.status())) {
                open++;
                byKind.merge(e.kind(), 1L, (x, y) -> (Long) x + (Long) y);
            }
        }
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("open", open);
        out.put("byKind", byKind);
        out.put("up", up);
        out.put("down", down);
        out.put("counted", rows.size());
        out.put("sampled", rows.size() >= MAX_LIST);
        return out;
    }

    private static final Pattern ID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    /** Marks one entry {@code open} or {@code done} (tenant-wide ADMIN). */
    public Map<String, Object> setStatus(Principal p, String id, String status) {
        admin(p);
        if (id == null || !ID.matcher(id).matches()) {
            throw ApiException.badRequest("id is not a feedback id");
        }
        if (!FeedbackEntry.OPEN.equals(status) && !FeedbackEntry.DONE.equals(status)) {
            throw ApiException.badRequest("status must be \"open\" or \"done\"");
        }
        if (!store.setStatus(p.tenantId(), id, status)) {
            throw new ApiException(ApiException.Code.NOT_FOUND, "No such feedback");
        }
        return Map.of("id", id, "status", status);
    }

    /**
     * The tenant's newest feedback as CSV (ADMIN): {@code time,user,kind,rating,status,message,context}, context as {@code key=value; ...}.
     * Cells that a spreadsheet would run as a formula (starting with {@code = + - @}, tab or return) get a leading apostrophe, and every cell is
     * quoted, so exported feedback cannot execute anything when opened.
     */
    public String exportCsv(Principal p, String kind, int limit) {
        List<FeedbackEntry> rows = list(p, kind, limit, null);
        StringBuilder b = new StringBuilder("time,user,kind,rating,status,message,context\r\n");
        for (FeedbackEntry e : rows) {
            StringBuilder ctx = new StringBuilder();
            e.context().entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .forEach(c -> ctx.append(ctx.length() == 0 ? "" : "; ").append(c.getKey()).append('=').append(c.getValue()));
            b.append(cell(e.time().toString())).append(',').append(cell(e.user())).append(',').append(cell(e.kind())).append(',')
                    .append(cell(e.rating() == null ? "" : e.rating())).append(',').append(cell(e.status())).append(',').append(cell(e.message())).append(',')
                    .append(cell(ctx.toString())).append("\r\n");
        }
        return b.toString();
    }

    static String cell(String s) {
        String t = s;
        if (!t.isEmpty() && "=+-@\t\r".indexOf(t.charAt(0)) >= 0) {
            t = "'" + t;
        }
        return '"' + t.replace("\"", "\"\"") + '"';
    }

    private static void admin(Principal p) {
        if (p == null) {
            throw new ApiException(ApiException.Code.UNAUTHORIZED, "Not authenticated");
        }
        if (!p.can(Permission.ADMIN)) {
            throw ApiException.forbidden(Permission.ADMIN);
        }
    }

    /**
     * Deletes this tenant's feedback older than {@code before} (tenant-wide ADMIN). The cut-off is required and must be at least a day old,
     * so one call cannot wipe what people sent today.
     *
     * @return {@code {deleted}}
     */
    public Map<String, Object> purge(Principal p, Instant before) {
        if (p == null) {
            throw new ApiException(ApiException.Code.UNAUTHORIZED, "Not authenticated");
        }
        if (!p.can(Permission.ADMIN)) {
            throw ApiException.forbidden(Permission.ADMIN);
        }
        if (before == null) {
            throw ApiException.badRequest("before is required");
        }
        if (before.isAfter(Instant.now(clock).minus(java.time.Duration.ofDays(1)))) {
            throw ApiException.badRequest("before must be at least one day ago");
        }
        return Map.of("deleted", store.purgeBefore(p.tenantId(), before));
    }

    /**
     * Masks what looks like a credential: Authorization/Bearer values, {@code token=...}-style pairs, URL passwords and query strings, JWTs and
     * well-known token shapes, and any 40+ character run of token characters. It is a safety net for text people paste, not a guarantee, and
     * it errs on the side of masking. Control characters other than newline and tab are dropped.
     */
    public static String scrub(String s) {
        String t = s.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "");
        t = AUTH.matcher(t).replaceAll("$1$2***");
        t = NAMED.matcher(t).replaceAll("$1$2***");
        t = USERINFO.matcher(t).replaceAll("$1***@");
        t = URL_QUERY.matcher(t).replaceAll("$1?***");
        t = JWT.matcher(t).replaceAll("***");
        t = KNOWN_TOKENS.matcher(t).replaceAll("***");
        return t;
    }
}
