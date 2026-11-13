package tech.kayys.wayang.embed;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import tech.kayys.wayang.runtime.json.Json;

/**
 * The release as a small HTTP service (JDK {@code HttpServer}, no framework). One tenant, one API key.
 *
 * <pre>
 * GET  /healthz                      liveness (no auth)
 * GET  /readyz                       200 when every secret the release needs is present, else 503 (no auth, counts only)
 * GET  /v1/release                   what this release is: project, processes and versions, bundle hash, missing secret names
 * POST /v1/processes/{id}/runs       {"input": {...}}  -> the run (201), possibly WAITING with a task token
 * GET  /v1/runs[?processId=&amp;limit=]  newest first
 * GET  /v1/runs/{id}
 * GET  /v1/triggers                  what each trigger is doing (no configuration)
 * GET  /v1/ratings                   thumbs up and down on chat replies, counted per trigger (no chat ids, no text)
 * GET|POST /hooks/{id}               a webhook trigger; no API key: authenticated by its shared secret or provider signature
 * POST /v1/runs/{id}/cancel          a parked run only
 * POST /v1/tasks/{token}/complete    {"outputs": {...}}
 * </pre>
 *
 * Everything under {@code /v1} needs {@code Authorization: Bearer <key>}. Without a key the service starts only on a loopback
 * address. Bodies are capped, errors carry a message and never a stack trace, and the key is compared in constant time.
 */
public final class HttpService implements AutoCloseable {

    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_.-]{1,100}");
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{1,200}");

    private final ReleaseEngine rt;
    private final byte[] keyDigest;
    private final InetSocketAddress bind;
    private final int maxBody;
    private HttpServer server;
    private ScheduledExecutorService ticker;
    private ThreadPoolExecutor pool;
    private ReleaseTriggers triggers; // nullable

    /** @param apiKey null or blank means no authentication (loopback only) */
    public HttpService(ReleaseEngine rt, String apiKey, InetSocketAddress bind, int maxBodyBytes) {
        this.rt = rt;
        this.bind = bind;
        this.maxBody = Math.max(1024, maxBodyBytes);
        this.keyDigest = apiKey == null || apiKey.isBlank() ? null : sha256(apiKey.strip());
        if (keyDigest == null && !bind.getAddress().isLoopbackAddress()) {
            throw new IllegalStateException("Refusing to listen on " + bind.getAddress().getHostAddress()
                    + " without an API key: set WAYANG_API_KEY (or WAYANG_BIND=127.0.0.1 to keep it local)");
        }
    }

    /** Serves the release's webhook routes under {@code /hooks/<id>} and starts its poll loops. Call before {@link #start()}. */
    public HttpService withTriggers(ReleaseTriggers t) {
        this.triggers = t;
        return this;
    }

    public synchronized HttpService start() throws IOException {
        server = HttpServer.create(bind, 128);
        pool = new ThreadPoolExecutor(4, 16, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(128));
        server.setExecutor(pool);
        server.createContext("/", this::handle);
        server.start();
        if (triggers != null) {
            triggers.start();
        }
        ticker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "wayang-timers");
            t.setDaemon(true);
            return t;
        });
        ticker.scheduleWithFixedDelay(() -> {
            try {
                rt.fireDueTimers();
            } catch (RuntimeException e) {
                // a failing timer must not stop the ticker
            }
        }, 1, 1, TimeUnit.SECONDS);
        return this;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public synchronized void close() {
        if (ticker != null) {
            ticker.shutdownNow();
        }
        if (triggers != null) {
            triggers.close();
        }
        if (server != null) {
            server.stop(1);
        }
        if (pool != null) {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ routing

    private void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            String m = ex.getRequestMethod();
            if (path.equals("/healthz")) {
                only(ex, m, "GET");
                send(ex, 200, Map.of("status", "UP"));
            } else if (path.equals("/readyz")) {
                only(ex, m, "GET");
                int missing = rt.missingSecrets().size();
                boolean ready = missing == 0 && rt.ready();
                send(ex, ready ? 200 : 503, Map.of("ready", ready, "missingSecrets", missing));
            } else if (path.startsWith("/hooks/")) {
                hook(ex, m, path.substring(7));
            } else if (path.startsWith("/v1/")) {
                if (!authorised(ex)) {
                    ex.getResponseHeaders().set("WWW-Authenticate", "Bearer");
                    throw new Http(401, "Missing or wrong API key");
                }
                v1(ex, m, path.substring(4).split("/", -1));
            } else {
                throw new Http(404, "Not found");
            }
        } catch (Http h) {
            send(ex, h.status, Map.of("error", h.getMessage()));
        } catch (ReleaseEngine.Unavailable u) {
            send(ex, 503, Map.of("error", u.getMessage()));
        } catch (RuntimeException e) {
            send(ex, 500, Map.of("error", "Internal error"));
        } finally {
            ex.close();
        }
    }

    /** Webhooks carry their own authentication (shared secret or provider signature), not the API key. */
    private void hook(HttpExchange ex, String m, String id) throws IOException {
        if (triggers == null || !ID.matcher(id).matches()) {
            throw new Http(404, "Not found");
        }
        if (!m.equals("GET") && !m.equals("POST")) {
            ex.getResponseHeaders().set("Allow", "GET, POST");
            throw new Http(405, "Method not allowed");
        }
        byte[] raw = ex.getRequestBody().readNBytes(maxBody + 1);
        if (raw.length > maxBody) {
            throw new Http(413, "Body is larger than " + maxBody + " bytes");
        }
        Map<String, String> headers = new LinkedHashMap<>();
        ex.getRequestHeaders().forEach((k, v) -> {
            if (!v.isEmpty()) {
                headers.put(k, v.get(0));
            }
        });
        ReleaseTriggers.Reception r;
        try {
            r = triggers.receive(id, m, query(ex), headers, raw);
        } catch (ReleaseTriggers.Refused e) {
            throw new Http(e.status(), e.getMessage());
        }
        byte[] b = r.body().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", r.json() ? "application/json; charset=utf-8" : "text/plain; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.sendResponseHeaders(r.status(), b.length);
        try (OutputStream o = ex.getResponseBody()) {
            o.write(b);
        }
    }

    private void v1(HttpExchange ex, String m, String[] p) throws IOException {
        if (p.length == 1 && p[0].equals("release")) {
            only(ex, m, "GET");
            Map<String, Object> procs = new LinkedHashMap<>();
            rt.processIds().forEach(id -> procs.put(id, rt.version(id)));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("name", rt.bundle().name());
            out.put("bundleSha256", rt.bundle().sha256());
            out.put("processes", procs);
            out.put("missingSecrets", rt.missingSecrets());
            send(ex, 200, out);
        } else if (p.length == 1 && p[0].equals("triggers")) {
            only(ex, m, "GET");
            send(ex, 200, Map.of("triggers", triggers == null ? java.util.List.of() : triggers.status()));
        } else if (p.length == 1 && p[0].equals("ratings")) {
            only(ex, m, "GET");
            send(ex, 200, triggers == null ? Map.of("up", 0, "down", 0, "triggers", Map.of()) : triggers.ratings());
        } else if (p.length == 3 && p[0].equals("processes") && p[2].equals("runs")) {
            only(ex, m, "POST");
            String id = id(p[1]);
            Map<String, Object> body = body(ex);
            Object in = body.get("input");
            if (in != null && !(in instanceof Map)) {
                throw new Http(400, "\"input\" must be an object");
            }
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> input = in == null ? Map.of() : (Map<String, Object>) in;
                send(ex, 201, rt.start(id, input).toMap());
            } catch (IllegalArgumentException e) {
                throw new Http(404, "No such process");
            } catch (ReleaseEngine.Unavailable e) {
                throw e;
            } catch (IllegalStateException e) {
                throw new Http(429, "Too many parked runs");
            }
        } else if (p.length == 1 && p[0].equals("runs")) {
            only(ex, m, "GET");
            Map<String, String> q = query(ex);
            String proc = q.get("processId");
            if (proc != null) {
                id(proc);
            }
            int limit = 50;
            if (q.get("limit") != null) {
                try {
                    limit = Math.max(1, Math.min(200, Integer.parseInt(q.get("limit"))));
                } catch (NumberFormatException e) {
                    throw new Http(400, "limit must be a number");
                }
            }
            send(ex, 200, Map.of("runs", rt.list(proc, limit).stream().map(ReleaseEngine.RunInfo::toMap).toList()));
        } else if (p.length == 2 && p[0].equals("runs")) {
            only(ex, m, "GET");
            send(ex, 200, rt.get(id(p[1])).orElseThrow(() -> new Http(404, "No such run")).toMap());
        } else if (p.length == 3 && p[0].equals("runs") && p[2].equals("cancel")) {
            only(ex, m, "POST");
            if (!rt.cancel(id(p[1]))) {
                throw new Http(409, "Only a run that is waiting can be cancelled");
            }
            send(ex, 200, rt.get(p[1]).orElseThrow().toMap());
        } else if (p.length == 3 && p[0].equals("tasks") && p[2].equals("complete")) {
            only(ex, m, "POST");
            if (!TOKEN.matcher(p[1]).matches()) {
                throw new Http(400, "Bad task token");
            }
            Object out = body(ex).get("outputs");
            if (out != null && !(out instanceof Map)) {
                throw new Http(400, "\"outputs\" must be an object");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> outputs = out == null ? Map.of() : (Map<String, Object>) out;
            if (!rt.completeWait(p[1], outputs)) {
                throw new Http(404, "No run is waiting on that task");
            }
            send(ex, 200, Map.of("completed", true));
        } else {
            throw new Http(404, "Not found");
        }
    }

    // ------------------------------------------------------------------ plumbing

    private static final class Http extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final int status;

        Http(int status, String message) {
            super(message, null, false, false);
            this.status = status;
        }
    }

    private static void only(HttpExchange ex, String actual, String expected) {
        if (!actual.equals(expected)) {
            ex.getResponseHeaders().set("Allow", expected);
            throw new Http(405, "Method not allowed");
        }
    }

    private static String id(String s) {
        if (!ID.matcher(s).matches()) {
            throw new Http(400, "Bad id");
        }
        return s;
    }

    private boolean authorised(HttpExchange ex) {
        if (keyDigest == null) {
            return true;
        }
        String h = ex.getRequestHeaders().getFirst("Authorization");
        if (h == null || !h.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return false;
        }
        return MessageDigest.isEqual(keyDigest, sha256(h.substring(7).strip()));
    }

    private Map<String, Object> body(HttpExchange ex) throws IOException {
        byte[] b = ex.getRequestBody().readNBytes(maxBody + 1);
        if (b.length > maxBody) {
            throw new Http(413, "Body is larger than " + maxBody + " bytes");
        }
        if (b.length == 0) {
            return Map.of();
        }
        String ct = ex.getRequestHeaders().getFirst("Content-Type");
        if (ct == null || !ct.toLowerCase(java.util.Locale.ROOT).contains("json")) {
            throw new Http(415, "Send application/json");
        }
        Object o;
        try {
            o = Json.read(new String(b, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            throw new Http(400, "The body is not valid JSON");
        }
        if (!(o instanceof Map<?, ?> m)) {
            throw new Http(400, "The body must be a JSON object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) m;
        return r;
    }

    private static Map<String, String> query(HttpExchange ex) {
        Map<String, String> q = new LinkedHashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw != null) {
            for (String kv : raw.split("&")) {
                int i = kv.indexOf('=');
                if (i > 0) {
                    q.put(java.net.URLDecoder.decode(kv.substring(0, i), StandardCharsets.UTF_8),
                            java.net.URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8));
                }
            }
        }
        return q;
    }

    private static void send(HttpExchange ex, int status, Object json) throws IOException {
        byte[] b = Json.write(json).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream o = ex.getResponseBody()) {
            o.write(b);
        }
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Resolves the bind address from a host string; unresolvable hosts are refused rather than guessed. */
    public static InetSocketAddress address(String host, int port) {
        try {
            return new InetSocketAddress(InetAddress.getByName(host), port);
        } catch (java.net.UnknownHostException e) {
            throw new IllegalArgumentException("Cannot resolve the bind address");
        }
    }
}
