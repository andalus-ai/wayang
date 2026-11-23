package tech.kayys.wayang.api;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves requests for the Studio web build (a directory produced by {@code flutter build web}) for the all-in-one server. Pure
 * file logic, no HTTP types: a path is served only if it stays inside the directory (no {@code ..}, no absolute path, no backslash,
 * no NUL, no symlink out); a path that is not a file falls back to {@code index.html} (the app routes in the browser) unless it looks
 * like an asset request (has an extension), which is a plain 404.
 */
public final class StudioFiles {

    /** Flutter web needs inline bootstrap, WebAssembly (CanvasKit) and blob/data images; nothing may come from another origin. */
    public static final String CSP = "default-src 'self'; script-src 'self' 'unsafe-inline' 'wasm-unsafe-eval'; style-src 'self' 'unsafe-inline'; "
            + "img-src 'self' data: blob:; font-src 'self' data:; connect-src 'self' ws: wss:; worker-src 'self' blob:; frame-ancestors 'none'";

    private static final Map<String, String> TYPES = Map.ofEntries(
            Map.entry("html", "text/html; charset=utf-8"), Map.entry("js", "text/javascript; charset=utf-8"),
            Map.entry("mjs", "text/javascript; charset=utf-8"), Map.entry("css", "text/css; charset=utf-8"),
            Map.entry("json", "application/json"), Map.entry("wasm", "application/wasm"), Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"), Map.entry("jpeg", "image/jpeg"), Map.entry("gif", "image/gif"),
            Map.entry("svg", "image/svg+xml"), Map.entry("ico", "image/x-icon"), Map.entry("woff", "font/woff"),
            Map.entry("woff2", "font/woff2"), Map.entry("ttf", "font/ttf"), Map.entry("otf", "font/otf"),
            Map.entry("txt", "text/plain; charset=utf-8"), Map.entry("map", "application/json"));

    /** A file to send. */
    public record Served(Path file, String contentType) {
    }

    private final Path root;

    /** @throws IllegalArgumentException when the directory is missing or has no index.html */
    public StudioFiles(Path dir) {
        if (dir == null || !Files.isRegularFile(dir.resolve("index.html"))) {
            throw new IllegalArgumentException("wayang.studio.dir must be a Flutter web build (a directory with index.html)");
        }
        try {
            this.root = dir.toRealPath();
        } catch (IOException e) {
            throw new IllegalArgumentException("wayang.studio.dir is not readable");
        }
    }

    /** @param requestPath the URL path as received (not decoded twice), for example {@code /main.dart.js} or {@code /runs/42} */
    public Optional<Served> resolve(String requestPath) {
        String p = requestPath == null ? "/" : requestPath;
        if (p.indexOf('\\') >= 0 || p.indexOf('\0') >= 0 || p.indexOf('%') >= 0 || !p.startsWith("/")) {
            return Optional.empty();
        }
        String rel = p.substring(1);
        for (String seg : rel.split("/", -1)) {
            if (seg.equals("..") || seg.equals(".")) {
                return Optional.empty();
            }
        }
        if (rel.isEmpty() || rel.endsWith("/")) {
            return serve(root.resolve("index.html"));
        }
        Path f = root.resolve(rel).normalize();
        Optional<Served> direct = serve(f);
        if (direct.isPresent()) {
            return direct;
        }
        String last = rel.substring(rel.lastIndexOf('/') + 1);
        if (last.indexOf('.') > 0) {
            return Optional.empty(); // a missing asset is a 404, not the app
        }
        return serve(root.resolve("index.html"));
    }

    /** Cache policy: the entry points must be revalidated, hashed or versioned assets may be cached briefly. */
    public static String cacheControl(Path file) {
        String n = file.getFileName().toString();
        return n.equals("index.html") || n.equals("flutter_service_worker.js") || n.equals("main.dart.js") || n.equals("version.json")
                ? "no-cache" : "public, max-age=3600";
    }

    private Optional<Served> serve(Path f) {
        try {
            if (!f.startsWith(root) || !Files.isRegularFile(f)) {
                return Optional.empty();
            }
            Path real = f.toRealPath();
            if (!real.startsWith(root)) {
                return Optional.empty(); // a symlink leaving the directory
            }
            String name = real.getFileName().toString();
            int dot = name.lastIndexOf('.');
            String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
            return Optional.of(new Served(real, TYPES.getOrDefault(ext, "application/octet-stream")));
        } catch (IOException | java.nio.file.InvalidPathException e) {
            return Optional.empty();
        }
    }
}
