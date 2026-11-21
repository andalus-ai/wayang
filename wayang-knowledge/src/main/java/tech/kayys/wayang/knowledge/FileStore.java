package tech.kayys.wayang.knowledge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

import tech.kayys.wayang.rag.RagException;

/**
 * Durable, tenant-separated storage for files that workflows and agents share (documents to ingest, reports, uploads). Each file gets a random
 * id; names are metadata only and never become paths, so a name cannot escape the directory. Bounded: 20 MiB per file, 1000 files per tenant.
 * Optional retention: a file may expire after {@code ttlDays}; {@code WAYANG_FILES_TTL_DAYS} sets a default and {@code WAYANG_FILES_MAX_TTL_DAYS} a
 * ceiling no file may exceed. An expired file is gone for every reader at once and is deleted by the next store, list or purge.
 *
 * <p>Directory: {@code WAYANG_FILES_DIR}, else {@code WAYANG_STATE_DIR/files}. With neither set there is no storage and the file steps say so
 * instead of silently writing somewhere temporary. In a container, mount a volume there or the files go when the container does.
 */
final class FileStore {

    static final long MAX_BYTES = 20L << 20;
    static final int MAX_FILES = 1000;
    private static final Pattern ID = Pattern.compile("[0-9a-f]{24}");
    private static final SecureRandom RANDOM = new SecureRandom();

    record Stored(String id, String name, long size, String sha256, byte[] bytes) {
    }

    java.time.Clock clock() {
        return clock;
    }

    /** {@code expiresAt} is epoch milliseconds, 0 when the file never expires. */
    record Info(String id, String name, long size, String sha256, long expiresAt) {
    }

    static final int MAX_TTL_DAYS = 3650;

    private final Path root;
    private final java.time.Clock clock;
    private final int defaultTtlDays;
    private final int maxTtlDays;

    FileStore(Path root) {
        this(root, java.time.Clock.systemUTC(), 0, 0);
    }

    /** @param defaultTtlDays expiry of files stored without one (0 = never); @param maxTtlDays longest life any file may have (0 = no cap) */
    FileStore(Path root, java.time.Clock clock, int defaultTtlDays, int maxTtlDays) {
        this.root = root;
        this.clock = clock;
        this.defaultTtlDays = defaultTtlDays;
        this.maxTtlDays = maxTtlDays;
    }

    static FileStore fromEnvironment() {
        String d = System.getenv("WAYANG_FILES_DIR");
        String s = System.getenv("WAYANG_STATE_DIR");
        Path root = d != null && !d.isBlank() ? Path.of(d) : s != null && !s.isBlank() ? Path.of(s, "files") : null;
        return root == null ? null
                : new FileStore(root, java.time.Clock.systemUTC(), days("WAYANG_FILES_TTL_DAYS"), days("WAYANG_FILES_MAX_TTL_DAYS"));
    }

    private static int days(String variable) {
        String v = System.getenv(variable);
        try {
            int n = v == null || v.isBlank() ? 0 : Integer.parseInt(v.trim());
            return n < 0 || n > MAX_TTL_DAYS ? 0 : n;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * @param ttlDays how long to keep the file (1..3650), or null for the default; the configured maximum always applies
     */
    Info put(String tenant, String fileName, byte[] bytes, Integer ttlDays) {
        String name = cleanName(fileName);
        if (ttlDays != null && (ttlDays < 1 || ttlDays > MAX_TTL_DAYS)) {
            throw new RagException("ttlDays must be between 1 and " + MAX_TTL_DAYS);
        }
        int ttl = ttlDays != null ? ttlDays : defaultTtlDays;
        if (maxTtlDays > 0 && (ttl == 0 || ttl > maxTtlDays)) {
            ttl = maxTtlDays;
        }
        long expiresAt = ttl == 0 ? 0 : clock.millis() + ttl * 86_400_000L;
        purgeExpired(tenant);
        if (bytes.length > MAX_BYTES) {
            throw new RagException("file is larger than " + (MAX_BYTES >> 20) + " MiB");
        }
        try {
            Path dir = dir(tenant);
            Files.createDirectories(dir);
            if (count(dir) >= MAX_FILES) {
                throw new RagException("too many files (max " + MAX_FILES + "); delete some first");
            }
            byte[] raw = new byte[12];
            RANDOM.nextBytes(raw);
            String id = HexFormat.of().formatHex(raw);
            String sha = sha256(bytes);
            Path tmp = Files.createTempFile(dir, ".upload", ".tmp");
            Files.write(tmp, bytes);
            Files.move(tmp, dir.resolve(id + ".bin"), StandardCopyOption.ATOMIC_MOVE);
            Files.writeString(dir.resolve(id + ".meta"), name + "\n" + sha + "\n" + bytes.length + "\n" + expiresAt + "\n", StandardCharsets.UTF_8);
            return new Info(id, name, bytes.length, sha, expiresAt);
        } catch (IOException e) {
            throw new RagException("the file could not be stored");
        }
    }

    /** The stored file, or null when there is no such id for this tenant. */
    Stored get(String tenant, String id) {
        Info i = info(tenant, id);
        if (i == null) {
            return null;
        }
        try {
            return new Stored(i.id(), i.name(), i.size(), i.sha256(), Files.readAllBytes(dir(tenant).resolve(id + ".bin")));
        } catch (IOException e) {
            return null;
        }
    }

    Info info(String tenant, String id) {
        if (id == null || !ID.matcher(id).matches()) {
            return null;
        }
        try {
            Path meta = dir(tenant).resolve(id + ".meta");
            if (!Files.isRegularFile(meta) || !Files.isRegularFile(dir(tenant).resolve(id + ".bin"))) {
                return null;
            }
            String[] l = Files.readString(meta, StandardCharsets.UTF_8).split("\n");
            long expiresAt = l.length > 3 ? Long.parseLong(l[3].strip()) : 0;
            if (expiresAt > 0 && clock.millis() >= expiresAt) {
                return null; // expired: gone for every reader, removed by the next purge
            }
            return new Info(id, l[0], Long.parseLong(l[2]), l[1], expiresAt);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Removes this tenant's expired files; returns how many. Runs on every store and list, and on demand (file-purge). */
    int purgeExpired(String tenant) {
        Path dir = dir(tenant);
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        int n = 0;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.meta")) {
            for (Path p : ds) {
                String f = p.getFileName().toString();
                String id = f.substring(0, f.length() - 5);
                if (ID.matcher(id).matches() && info(tenant, id) == null && isExpired(p)) {
                    Files.deleteIfExists(dir.resolve(id + ".bin"));
                    Files.deleteIfExists(p);
                    n++;
                }
            }
        } catch (IOException e) {
            throw new RagException("expired files could not be removed");
        }
        return n;
    }

    private boolean isExpired(Path meta) {
        try {
            String[] l = Files.readString(meta, StandardCharsets.UTF_8).split("\n");
            return l.length > 3 && Long.parseLong(l[3].strip()) > 0 && clock.millis() >= Long.parseLong(l[3].strip());
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    List<Info> list(String tenant) {
        purgeExpired(tenant);
        List<Info> out = new ArrayList<>();
        Path dir = dir(tenant);
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.meta")) {
            for (Path p : ds) {
                String f = p.getFileName().toString();
                Info i = info(tenant, f.substring(0, f.length() - 5));
                if (i != null) {
                    out.add(i);
                }
            }
        } catch (IOException e) {
            throw new RagException("the files could not be listed");
        }
        out.sort(java.util.Comparator.comparing(Info::name).thenComparing(Info::id));
        return out;
    }

    boolean delete(String tenant, String id) {
        if (info(tenant, id) == null) {
            return false;
        }
        try {
            Files.deleteIfExists(dir(tenant).resolve(id + ".bin"));
            Files.deleteIfExists(dir(tenant).resolve(id + ".meta"));
            return true;
        } catch (IOException e) {
            throw new RagException("the file could not be deleted");
        }
    }

    private Path dir(String tenant) {
        return root.resolve(tenantDirName(tenant));
    }

    /** The directory name of a tenant: a hash, so a tenant id never becomes part of a path. */
    static String tenantDirName(String tenant) {
        return sha256((tenant == null ? "" : tenant).getBytes(StandardCharsets.UTF_8)).substring(0, 32);
    }

    Path root() {
        return root;
    }

    private static long count(Path dir) throws IOException {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.bin")) {
            long n = 0;
            for (Path ignored : ds) {
                n++;
            }
            return n;
        }
    }

    /** A display name: last path segment only, no control characters, at most 200 characters. */
    static String cleanName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            throw new RagException("a file needs a name");
        }
        String n = fileName.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1).replaceAll("\\p{Cntrl}", "").strip();
        if (n.isEmpty() || n.equals(".") || n.equals("..")) {
            throw new RagException("a file needs a valid name");
        }
        return n.length() > 200 ? n.substring(0, 200) : n;
    }

    private static String sha256(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
