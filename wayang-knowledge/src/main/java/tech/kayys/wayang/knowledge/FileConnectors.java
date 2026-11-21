package tech.kayys.wayang.knowledge;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tech.kayys.wayang.rag.RagException;
import tech.kayys.wayang.runtime.ConnectorInput;
import tech.kayys.wayang.runtime.ServiceHandler;

/**
 * The reusable file storage steps: {@code file-put}, {@code file-get}, {@code file-list}, {@code file-delete}, {@code file-purge} (removes expired files; schedule it). Files are separated by tenant
 * and addressed by an opaque id, so one node can store a file and any later node (a RAG ingest, a mail step) can use it by id.
 * A missing mandatory input never fails a step: the step reports {@code stored:false} / {@code found:false} (and the engine adds a warning).
 */
final class FileConnectors {

    static final int MAX_INLINE = 8 << 20;

    private FileConnectors() {
    }

    /** ISO-8601 instant, or null when the file never expires. */
    static String expires(long epochMillis) {
        return epochMillis <= 0 ? null : java.time.Instant.ofEpochMilli(epochMillis).toString();
    }

    private abstract static class Base implements ServiceHandler.Connector {
        private final FileStore store;

        Base(FileStore store) {
            this.store = store;
        }

        FileStore store() throws ServiceHandler.ConnectorException {
            if (store == null) {
                throw new ServiceHandler.ConnectorException("file storage is not configured: set WAYANG_FILES_DIR (or WAYANG_STATE_DIR)", false);
            }
            return store;
        }

        static String tenant(Map<String, Object> config) {
            return config.get("wayang.tenant") instanceof String t ? t : "";
        }

        @Override
        public boolean tenantScoped() {
            return true;
        }

        @Override
        public Map<String, Object> call(Map<String, Object> inputs) throws Exception {
            throw new ServiceHandler.ConnectorException("this step needs node configuration", false);
        }

        static ServiceHandler.ConnectorException bad(RagException e) {
            return new ServiceHandler.ConnectorException(e.getMessage(), false);
        }
    }

    static final class Put extends Base {
        Put(FileStore s) {
            super(s);
        }

        @Override
        public List<ConnectorInput> inputs() {
            return List.of(ConnectorInput.required("fileName", "string", "Name of the file, with its extension"),
                    ConnectorInput.optional("contentBase64", "string", "File content, base64 (at most 8 MiB here)"),
                    ConnectorInput.optional("text", "string", "Text content, instead of contentBase64"),
                    ConnectorInput.optional("ttlDays", "number", "Keep the file this many days (1-3650); default and ceiling come from the server settings"));
        }

        @Override
        public Map<String, Object> call(Map<String, Object> config, Map<String, Object> in) throws Exception {
            FileStore st = store();
            Map<String, Object> out = new LinkedHashMap<>();
            if (!(in.get("fileName") instanceof String name) || name.isBlank()) {
                out.put("stored", false);
                out.put("reason", "no fileName");
                return out;
            }
            byte[] bytes;
            if (in.get("contentBase64") instanceof String b && !b.isBlank()) {
                if (b.length() > (long) MAX_INLINE * 4 / 3 + 16) {
                    throw new ServiceHandler.ConnectorException("content is larger than " + (MAX_INLINE >> 20) + " MiB", false);
                }
                try {
                    bytes = Base64.getMimeDecoder().decode(b);
                } catch (IllegalArgumentException e) {
                    throw new ServiceHandler.ConnectorException("contentBase64 is not valid base64", false);
                }
            } else if (in.get("text") instanceof String t && !t.isEmpty()) {
                bytes = t.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            } else {
                out.put("stored", false);
                out.put("reason", "no content");
                return out;
            }
            Integer ttl = null;
            if (in.get("ttlDays") != null && !(in.get("ttlDays") instanceof String s && s.isBlank())) {
                try {
                    ttl = in.get("ttlDays") instanceof Number n ? n.intValue() : Integer.valueOf(String.valueOf(in.get("ttlDays")).trim());
                } catch (NumberFormatException e) {
                    throw new ServiceHandler.ConnectorException("ttlDays must be a whole number of days", false);
                }
            }
            try {
                FileStore.Info i = st.put(tenant(config), name, bytes, ttl);
                out.put("stored", true);
                out.put("fileId", i.id());
                out.put("fileName", i.name());
                out.put("size", i.size());
                out.put("sha256", i.sha256());
                out.put("expiresAt", expires(i.expiresAt()));
                return out;
            } catch (RagException e) {
                throw bad(e);
            }
        }
    }

    /**
     * {@code file-fetch}: downloads a public URL into file storage (up to the 20 MiB storage limit) and returns the same fields as {@code file-put}.
     * The URL goes through the same SSRF guard as every connector (private and internal addresses are refused unless the operator allowed them);
     * redirects are not followed and no credentials are sent, so it is for public documents. A missing URL gives {@code stored:false}.
     */
    static final class Fetch extends Base {
        private final tech.kayys.wayang.connectors.MediaHttp http;

        Fetch(FileStore s, tech.kayys.wayang.connectors.UrlGuard guard) {
            super(s);
            this.http = new tech.kayys.wayang.connectors.MediaHttp(guard);
        }

        @Override
        public List<ConnectorInput> inputs() {
            return List.of(ConnectorInput.required("url", "string", "Public http(s) address of the document"),
                    ConnectorInput.optional("fileName", "string", "Name to store it under; default is the last part of the URL"),
                    ConnectorInput.optional("ttlDays", "number", "Keep the file this many days (1-3650)"));
        }

        @Override
        public Map<String, Object> call(Map<String, Object> config, Map<String, Object> in) throws Exception {
            FileStore st = store();
            Map<String, Object> out = new LinkedHashMap<>();
            if (!(in.get("url") instanceof String url) || url.isBlank()) {
                out.put("stored", false);
                out.put("reason", "no url");
                return out;
            }
            java.net.URI uri = http.check(url.trim());
            String name = in.get("fileName") instanceof String n && !n.isBlank() ? n : nameOf(uri);
            Integer ttl = null;
            if (in.get("ttlDays") != null && !(in.get("ttlDays") instanceof String t && t.isBlank())) {
                try {
                    ttl = in.get("ttlDays") instanceof Number n ? n.intValue() : Integer.valueOf(String.valueOf(in.get("ttlDays")).trim());
                } catch (NumberFormatException e) {
                    throw new ServiceHandler.ConnectorException("ttlDays must be a whole number of days", false);
                }
            }
            byte[] bytes = http.getBytes(uri, Map.of(), 60_000, FileStore.MAX_BYTES);
            if (bytes.length == 0) {
                out.put("stored", false);
                out.put("reason", "the address returned no content");
                return out;
            }
            try {
                FileStore.Info i = st.put(tenant(config), name, bytes, ttl);
                out.put("stored", true);
                out.put("fileId", i.id());
                out.put("fileName", i.name());
                out.put("size", i.size());
                out.put("sha256", i.sha256());
                out.put("expiresAt", expires(i.expiresAt()));
                return out;
            } catch (RagException e) {
                throw bad(e);
            }
        }

        private static String nameOf(java.net.URI uri) {
            String p = uri.getPath() == null ? "" : uri.getPath();
            String last = p.substring(p.lastIndexOf('/') + 1);
            return last.isBlank() ? "download" : last;
        }
    }

    static final class Get extends Base {
        Get(FileStore s) {
            super(s);
        }

        @Override
        public List<ConnectorInput> inputs() {
            return List.of(ConnectorInput.required("fileId", "string", "Id returned by file-put"));
        }

        @Override
        public Map<String, Object> call(Map<String, Object> config, Map<String, Object> in) throws Exception {
            FileStore st = store();
            Map<String, Object> out = new LinkedHashMap<>();
            FileStore.Stored f = in.get("fileId") instanceof String id ? st.get(tenant(config), id) : null;
            if (f == null) {
                out.put("found", false);
                return out;
            }
            out.put("found", true);
            out.put("fileId", f.id());
            out.put("fileName", f.name());
            out.put("size", f.size());
            out.put("sha256", f.sha256());
            out.put("expiresAt", expires(st.info(tenant(config), f.id()) == null ? 0 : st.info(tenant(config), f.id()).expiresAt()));
            if (f.size() <= MAX_INLINE) {
                out.put("contentBase64", Base64.getEncoder().encodeToString(f.bytes()));
            } else {
                out.put("contentOmitted", true);
            }
            return out;
        }
    }

    static final class ListFiles extends Base {
        ListFiles(FileStore s) {
            super(s);
        }

        @Override
        public Map<String, Object> call(Map<String, Object> config, Map<String, Object> in) throws Exception {
            List<Map<String, Object>> files = new ArrayList<>();
            try {
                for (FileStore.Info i : store().list(tenant(config))) {
                    Map<String, Object> f = new LinkedHashMap<>(Map.of("fileId", i.id(), "fileName", i.name(), "size", i.size()));
                    f.put("expiresAt", expires(i.expiresAt()));
                    files.add(f);
                }
            } catch (RagException e) {
                throw bad(e);
            }
            return Map.of("files", files, "count", files.size());
        }
    }

    static final class Delete extends Base {
        Delete(FileStore s) {
            super(s);
        }

        @Override
        public List<ConnectorInput> inputs() {
            return List.of(ConnectorInput.required("fileId", "string", "Id of the file to delete"));
        }

        @Override
        public Map<String, Object> call(Map<String, Object> config, Map<String, Object> in) throws Exception {
            try {
                return Map.of("deleted", in.get("fileId") instanceof String id && store().delete(tenant(config), id));
            } catch (RagException e) {
                throw bad(e);
            }
        }
    }

    static final class Purge extends Base {
        Purge(FileStore s) {
            super(s);
        }

        @Override
        public Map<String, Object> call(Map<String, Object> config, Map<String, Object> in) throws Exception {
            try {
                return Map.of("purged", store().purgeExpired(tenant(config)));
            } catch (RagException e) {
                throw bad(e);
            }
        }
    }
}
