package tech.kayys.wayang.endpoints;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tech.kayys.wayang.runtime.ConnectorInput;
import tech.kayys.wayang.runtime.ServiceHandler;
import tech.kayys.wayang.runtime.ServiceHandler.ConnectorException;

/** The file endpoint connectors: {@code fs-read}, {@code fs-write}, {@code fs-list}, {@code fs-move}, {@code fs-delete}. All confined by {@link FsRoot}. */
final class FsConnectors {

    static final int DEFAULT_MAX_BYTES = 8 * 1024 * 1024;
    static final int HARD_MAX_BYTES = 32 * 1024 * 1024;
    static final int MAX_LIST = 1000;

    private FsConnectors() {}

    abstract static class Base implements ServiceHandler.Connector {
        final FsRoot root;

        Base(FsRoot root) {
            this.root = root;
        }

        @Override
        public boolean tenantScoped() {
            return true;
        }

        @Override
        public Map<String, Object> call(Map<String, Object> inputs) throws Exception {
            throw new ConnectorException("this step needs node configuration", false);
        }

        /** An input value, else the same key from the node config. */
        static String text(Map<String, Object> config, Map<String, Object> in, String key) {
            Object v = in.get(key) != null ? in.get(key) : config.get(key);
            return v == null ? null : String.valueOf(v);
        }

        static boolean flag(Map<String, Object> config, String key) {
            return Boolean.TRUE.equals(config.get(key)) || "true".equalsIgnoreCase(String.valueOf(config.get(key)));
        }

        static int maxBytes(Map<String, Object> config) throws ConnectorException {
            Object v = config.get("maxBytes");
            if (v == null || v instanceof String s && s.isBlank()) {
                return DEFAULT_MAX_BYTES;
            }
            try {
                int n = v instanceof Number x ? x.intValue() : Integer.parseInt(String.valueOf(v).trim());
                if (n < 1 || n > HARD_MAX_BYTES) {
                    throw new NumberFormatException();
                }
                return n;
            } catch (NumberFormatException e) {
                throw new ConnectorException("maxBytes must be a whole number from 1 to " + HARD_MAX_BYTES, false);
            }
        }

        static boolean base64(Map<String, Object> config) throws ConnectorException {
            String e = config.get("encoding") == null ? "utf-8" : String.valueOf(config.get("encoding")).trim().toLowerCase(java.util.Locale.ROOT);
            return switch (e) {
                case "utf-8", "utf8" -> false;
                case "base64" -> true;
                default -> throw new ConnectorException("encoding must be utf-8 or base64", false);
            };
        }

        static String iso(java.nio.file.attribute.FileTime t) {
            return Instant.ofEpochMilli(t.toMillis()).toString();
        }

        static ConnectorException io(String what, IOException e) {
            return new ConnectorException(what + " failed", false);
        }
    }

    static final class Read extends Base {
        Read(FsRoot r) {
            super(r);
        }

        @Override
        public List<ConnectorInput> inputs() {
            return List.of(ConnectorInput.required("path", "string", "File to read, relative to the endpoint directory"));
        }

        @Override
        public Map<String, Object> call(Map<String, Object> config, Map<String, Object> in) throws Exception {
            String rel = text(config, in, "path");
            Map<String, Object> out = new LinkedHashMap<>();
            if (rel == null || rel.isBlank()) {
                out.put("found", false);
                out.put("reason", "no path");
                return out;
            }
            Path p = root.resolve(FsRoot.tenant(config), rel);
            int max = maxBytes(config);
            boolean b64 = base64(config);
            if (!Files.isRegularFile(p)) {
                out.put("found", false);
                out.put("reason", "no such file");
                return out;
            }
            try {
                long size = Files.size(p);
                if (size > max) {
                    throw new ConnectorException("the file is larger than " + max + " bytes", false);
                }
                byte[] bytes = Files.readAllBytes(p);
                out.put("found", true);
                out.put("path", rel.trim());
                out.put("size", bytes.length);
                out.put("modifiedAt", iso(Files.getLastModifiedTime(p)));
                if (b64) {
                    out.put("base64", Base64.getEncoder().encodeToString(bytes));
                } else {
                    try {
                        out.put("text", StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString());
                    } catch (CharacterCodingException e) {
                        throw new ConnectorException("the file is not valid UTF-8 text; set encoding to base64", false);
                    }
                }
                return out;
            } catch (IOException e) {
                throw io("reading the file", e);
            }
        }
    }

    static final class Write extends Base {
        Write(FsRoot r) {
            super(r);
        }

        @Override
        public List<ConnectorInput> inputs() {
            return List.of(ConnectorInput.required("path", "string", "File to write, relative to the endpoint directory"),
                    ConnectorInput.required("content", "string", "Text (or base64 when encoding is base64)"));
        }

        @Override
        public Map<String, Object> call(Map<String, Object> config, Map<String, Object> in) throws Exception {
            String rel = text(config, in, "path");
            Map<String, Object> out = new LinkedHashMap<>();
            if (rel == null || rel.isBlank() || !(in.get("content") instanceof String content)) {
                out.put("written", false);
                out.put("reason", rel == null || rel.isBlank() ? "no path" : "no content");
                return out;
            }
            Path p = root.resolve(FsRoot.tenant(config), rel);
            if (p.equals(root.base(FsRoot.tenant(config)))) {
                throw new ConnectorException("path must name a file", false);
            }
            byte[] bytes;
            try {
                bytes = base64(config) ? Base64.getDecoder().decode(content) : content.getBytes(StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                throw new ConnectorException("content is not valid base64", false);
            }
            if (bytes.length > maxBytes(config)) {
                throw new ConnectorException("the content is larger than " + maxBytes(config) + " bytes", false);
            }
            boolean overwrite = flag(config, "overwrite");
            try {
                Files.createDirectories(p.getParent());
                root.resolve(FsRoot.tenant(config), FsRoot.relative(root.base(FsRoot.tenant(config)), p)); // the parent may have been created through a link: check again
                if (!overwrite && Files.exists(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    throw new ConnectorException("the file already exists (set overwrite to replace it)", false);
                }
                Path tmp = Files.createTempFile(p.getParent(), ".wayang-", ".tmp"); // a reader never sees half a file
                try {
                    Files.write(tmp, bytes);
                    if (overwrite) {
                        Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    } else {
                        Files.move(tmp, p);
                    }
                } finally {
                    Files.deleteIfExists(tmp);
                }
                out.put("written", true);
                out.put("path", FsRoot.relative(root.base(FsRoot.tenant(config)), p));
                out.put("size", bytes.length);
                return out;
            } catch (java.nio.file.FileAlreadyExistsException e) {
                throw new ConnectorException("the file already exists (set overwrite to replace it)", false);
            } catch (IOException e) {
                throw io("writing the file", e);
            }
        }
    }

    static final class ListFiles extends Base {
        ListFiles(FsRoot r) {
            super(r);
        }

        @Override
        public List<ConnectorInput> inputs() {
            return List.of(ConnectorInput.optional("path", "string", "Directory, relative to the endpoint directory (default: the directory itself)"),
                    ConnectorInput.optional("glob", "string", "File name pattern, for example *.csv (default *)"));
        }

        @Override
        public Map<String, Object> call(Map<String, Object> config, Map<String, Object> in) throws Exception {
            String rel = text(config, in, "path");
            String glob = text(config, in, "glob");
            Map<String, Object> out = new LinkedHashMap<>();
            Path dir = root.resolve(FsRoot.tenant(config), rel);
            List<Map<String, Object>> files = new ArrayList<>();
            out.put("files", files);
            if (!Files.isDirectory(dir)) {
                out.put("count", 0);
                out.put("truncated", false);
                return out;
            }
            Path base = root.base(FsRoot.tenant(config));
            boolean truncated = false;
            for (Path f : matching(dir, glob)) {
                if (files.size() >= MAX_LIST) {
                    truncated = true;
                    break;
                }
                BasicFileAttributes a = Files.readAttributes(f, BasicFileAttributes.class);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", f.getFileName().toString());
                m.put("path", FsRoot.relative(base, f));
                m.put("size", a.size());
                m.put("modifiedAt", iso(a.lastModifiedTime()));
                files.add(m);
            }
            out.put("count", files.size());
            out.put("truncated", truncated);
            return out;
        }
    }

    /** Regular files of {@code dir} whose name matches {@code glob}, sorted by name; temp files of this module are hidden. */
    static List<Path> matching(Path dir, String glob) throws ConnectorException {
        String g = glob == null || glob.isBlank() ? "*" : glob.trim();
        if (g.length() > 200 || g.contains("/") || g.contains("\\")) {
            throw new ConnectorException("glob is a file name pattern such as *.csv", false);
        }
        PathMatcher m;
        try {
            m = FileSystems.getDefault().getPathMatcher("glob:" + g);
        } catch (RuntimeException e) {
            throw new ConnectorException("glob is not a valid pattern", false);
        }
        List<Path> found = new ArrayList<>();
        try (DirectoryStream<Path> s = Files.newDirectoryStream(dir)) {
            for (Path f : s) {
                String n = f.getFileName().toString();
                if (Files.isRegularFile(f, java.nio.file.LinkOption.NOFOLLOW_LINKS) && m.matches(f.getFileName()) && !(n.startsWith(".wayang-") && n.endsWith(".tmp"))) {
                    found.add(f);
                }
            }
        } catch (IOException e) {
            throw new ConnectorException("listing the directory failed", false);
        }
        found.sort(Comparator.comparing(f -> f.getFileName().toString()));
        return found;
    }

    static final class Move extends Base {
        Move(FsRoot r) {
            super(r);
        }

        @Override
        public List<ConnectorInput> inputs() {
            return List.of(ConnectorInput.required("from", "string", "File to move, relative to the endpoint directory"),
                    ConnectorInput.required("to", "string", "New path (for example an archive folder)"));
        }

        @Override
        public Map<String, Object> call(Map<String, Object> config, Map<String, Object> in) throws Exception {
            String from = text(config, in, "from");
            String to = text(config, in, "to");
            Map<String, Object> out = new LinkedHashMap<>();
            if (from == null || from.isBlank() || to == null || to.isBlank()) {
                out.put("moved", false);
                out.put("reason", "from and to are needed");
                return out;
            }
            String tenant = FsRoot.tenant(config);
            Path src = root.resolve(tenant, from);
            Path dst = root.resolve(tenant, to);
            if (!Files.isRegularFile(src, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                out.put("moved", false);
                out.put("reason", "no such file");
                return out;
            }
            try {
                Files.createDirectories(dst.getParent());
                root.resolve(tenant, FsRoot.relative(root.base(tenant), dst));
                if (flag(config, "overwrite")) {
                    Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
                } else {
                    Files.move(src, dst);
                }
                out.put("moved", true);
                out.put("path", FsRoot.relative(root.base(tenant), dst));
                return out;
            } catch (java.nio.file.FileAlreadyExistsException e) {
                throw new ConnectorException("the target already exists (set overwrite to replace it)", false);
            } catch (IOException e) {
                throw io("moving the file", e);
            }
        }
    }

    static final class Delete extends Base {
        Delete(FsRoot r) {
            super(r);
        }

        @Override
        public List<ConnectorInput> inputs() {
            return List.of(ConnectorInput.required("path", "string", "File to delete, relative to the endpoint directory"));
        }

        @Override
        public Map<String, Object> call(Map<String, Object> config, Map<String, Object> in) throws Exception {
            String rel = text(config, in, "path");
            Map<String, Object> out = new LinkedHashMap<>();
            if (rel == null || rel.isBlank()) {
                out.put("deleted", false);
                out.put("reason", "no path");
                return out;
            }
            Path p = root.resolve(FsRoot.tenant(config), rel);
            if (!Files.isRegularFile(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                out.put("deleted", false); // only files: never a directory
                return out;
            }
            try {
                Files.delete(p);
                out.put("deleted", true);
                return out;
            } catch (IOException e) {
                throw io("deleting the file", e);
            }
        }
    }
}
