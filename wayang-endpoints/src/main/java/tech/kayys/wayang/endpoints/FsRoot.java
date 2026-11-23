package tech.kayys.wayang.endpoints;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.regex.Pattern;

import tech.kayys.wayang.runtime.ServiceHandler.ConnectorException;

/**
 * The operator-chosen directory every file endpoint is confined to (env {@code WAYANG_ENDPOINTS_FS_ROOT}). Each tenant gets its own
 * subdirectory, so one tenant can neither read nor list another's files. Paths are always relative; absolute paths, {@code ..} segments, NUL and
 * symbolic links that lead outside the tenant directory are refused. Not configured means every file endpoint is off.
 */
final class FsRoot {

    static final String ENV = "WAYANG_ENDPOINTS_FS_ROOT";
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private final Path root;

    FsRoot(Path root) {
        this.root = root;
    }

    static FsRoot fromEnvironment() {
        String v = System.getenv(ENV);
        return new FsRoot(v == null || v.isBlank() ? null : Path.of(v.trim()));
    }

    static String tenant(Map<String, Object> config) {
        return config.get("wayang.tenant") instanceof String t ? t : "";
    }

    /** The tenant's directory, created on first use. */
    Path base(String tenant) throws ConnectorException {
        if (root == null) {
            throw new ConnectorException("file endpoints are off: the operator must set " + ENV, false);
        }
        try {
            Files.createDirectories(root);
            Path dir = root.toRealPath().resolve(key(tenant));
            Files.createDirectories(dir);
            return dir.toRealPath();
        } catch (IOException e) {
            throw new ConnectorException("the file endpoint directory is not usable", false);
        }
    }

    /** {@code rel} inside the tenant directory ("" or "." is the directory itself); the result is never outside it. */
    Path resolve(String tenant, String rel) throws ConnectorException {
        Path base = base(tenant);
        String r = rel == null ? "" : rel.trim();
        if (r.indexOf('\0') >= 0 || r.indexOf('\\') >= 0 || r.startsWith("/") || r.matches("^[A-Za-z]:.*")) {
            throw new ConnectorException("path must be relative, inside the endpoint directory", false);
        }
        for (String seg : r.split("/")) {
            if (seg.equals("..")) {
                throw new ConnectorException("path must not contain '..'", false);
            }
        }
        Path p = base.resolve(r).normalize();
        if (!p.startsWith(base)) {
            throw new ConnectorException("path must be inside the endpoint directory", false);
        }
        try {
            Path existing = p;
            while (existing != null && !Files.exists(existing, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                existing = existing.getParent();
            }
            if (existing == null || !existing.toRealPath().startsWith(base)) {
                throw new ConnectorException("path leads outside the endpoint directory", false);
            }
        } catch (IOException e) {
            throw new ConnectorException("path is not usable", false);
        }
        return p;
    }

    /** The path relative to the tenant directory, with '/' separators. */
    static String relative(Path base, Path p) {
        return base.relativize(p).toString().replace('\\', '/');
    }

    private static String key(String tenant) {
        if (tenant == null || tenant.isEmpty()) {
            return "none";
        }
        if (SAFE.matcher(tenant).matches()) {
            return "u-" + tenant;
        }
        try {
            return "h-" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(tenant.getBytes(StandardCharsets.UTF_8))).substring(0, 24);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
