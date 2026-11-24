package tech.kayys.wayang.endpoints.sftp;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tech.kayys.wayang.runtime.ConnectorInput;
import tech.kayys.wayang.runtime.ServiceHandler;
import tech.kayys.wayang.runtime.ServiceHandler.ConnectorException;

/**
 * Connectors {@code sftp-list}, {@code sftp-read}, {@code sftp-write}, {@code sftp-delete}. Node config: {@code host}, {@code port},
 * {@code user}, {@code password} or {@code privateKey}, {@code hostKey} (see {@link Sftp}). Every call opens a session, does one thing and
 * closes it. Connection problems and timeouts are retryable; a refused path, a missing file or a permission error is not.
 */
final class SftpConnectors {

    private SftpConnectors() {
    }

    abstract static class Base implements ServiceHandler.Connector {
        final Sftp sftp;

        Base(Sftp sftp) {
            this.sftp = sftp;
        }

        @Override
        public Map<String, Object> call(Map<String, Object> inputs) throws Exception {
            throw new ConnectorException("this sftp step needs node configuration (host, user, hostKey)", false);
        }

        abstract Map<String, Object> run(Sftp.Session s, Map<String, Object> config, Map<String, Object> inputs) throws Exception;

        void validate(Map<String, Object> config, Map<String, Object> inputs) throws ConnectorException {
        }

        @Override
        public Map<String, Object> call(Map<String, Object> config, Map<String, Object> inputs) throws Exception {
            Sftp.Target target = sftp.target(config);
            validate(config, inputs); // refuse a bad path before connecting
            try (Sftp.Session s = sftp.opener.open(target)) {
                return run(s, config, inputs);
            } catch (ConnectorException e) {
                throw e;
            } catch (Exception e) {
                throw Sftp.fail("the sftp operation failed", e, Sftp.transientFailure(e));
            }
        }
    }

    static final class ListFiles extends Base {
        ListFiles(Sftp sftp) {
            super(sftp);
        }

        @Override
        public List<ConnectorInput> inputs() {
            return List.of(ConnectorInput.optional("path", "string", "Directory, relative to the account's home (default: the home directory)"));
        }

        @Override
        void validate(Map<String, Object> config, Map<String, Object> inputs) throws ConnectorException {
            Sftp.path(inputs.get("path"), true);
        }

        @Override
        Map<String, Object> run(Sftp.Session s, Map<String, Object> config, Map<String, Object> inputs) throws Exception {
            String dir = Sftp.path(inputs.get("path"), true);
            List<Map<String, Object>> files = new ArrayList<>();
            for (Sftp.Entry e : s.list(dir)) {
                if (e.file()) {
                    files.add(entry(dir, e));
                }
            }
            files.sort((a, b) -> String.valueOf(a.get("name")).compareTo(String.valueOf(b.get("name"))));
            return Map.of("path", dir, "files", files, "count", files.size());
        }
    }

    static Map<String, Object> entry(String dir, Sftp.Entry e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", e.name());
        m.put("path", dir.equals(".") ? e.name() : dir + "/" + e.name());
        m.put("size", e.size());
        m.put("mtime", e.mtimeSeconds());
        return m;
    }

    static final class Read extends Base {
        Read(Sftp sftp) {
            super(sftp);
        }

        @Override
        public List<ConnectorInput> inputs() {
            return List.of(ConnectorInput.required("path", "string", "File, relative to the account's home"),
                    ConnectorInput.optional("encoding", "string", "utf-8 (default, strict) or base64"),
                    ConnectorInput.optional("maxBytes", "integer", "Refuse larger files (default 8 MiB, at most 32 MiB)"));
        }

        @Override
        void validate(Map<String, Object> config, Map<String, Object> inputs) throws ConnectorException {
            Sftp.path(inputs.get("path"), false);
            limit(inputs);
        }

        private static int limit(Map<String, Object> inputs) throws ConnectorException {
            if (inputs.get("maxBytes") == null) {
                return Sftp.MAX_READ_BYTES;
            }
            try {
                int n = Integer.parseInt(String.valueOf(inputs.get("maxBytes")).trim());
                if (n < 1 || n > Sftp.HARD_MAX_BYTES) {
                    throw new NumberFormatException();
                }
                return n;
            } catch (NumberFormatException e) {
                throw new ConnectorException("maxBytes must be 1-" + Sftp.HARD_MAX_BYTES, false);
            }
        }

        @Override
        Map<String, Object> run(Sftp.Session s, Map<String, Object> config, Map<String, Object> inputs) throws Exception {
            String path = Sftp.path(inputs.get("path"), false);
            int max = limit(inputs);
            byte[] data = s.read(path, max);
            if (data == null) {
                throw new ConnectorException("the file is larger than " + max + " bytes", false);
            }
            boolean b64 = "base64".equalsIgnoreCase(String.valueOf(inputs.get("encoding")));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("path", path);
            out.put("size", (long) data.length);
            out.put("encoding", b64 ? "base64" : "utf-8");
            if (b64) {
                out.put("content", Base64.getEncoder().encodeToString(data));
            } else {
                try {
                    out.put("content", StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(data)).toString());
                } catch (CharacterCodingException e) {
                    throw new ConnectorException("the file is not valid UTF-8; read it with encoding base64", false);
                }
            }
            return out;
        }
    }

    static final class Write extends Base {
        Write(Sftp sftp) {
            super(sftp);
        }

        @Override
        public List<ConnectorInput> inputs() {
            return List.of(ConnectorInput.required("path", "string", "File to create, relative to the account's home"),
                    ConnectorInput.required("content", "string", "Text, or base64 when encoding is base64"),
                    ConnectorInput.optional("encoding", "string", "utf-8 (default) or base64"),
                    ConnectorInput.optional("overwrite", "boolean", "Replace an existing file (default false)"));
        }

        private static byte[] bytes(Map<String, Object> inputs) throws ConnectorException {
            if (!(inputs.get("content") instanceof String c)) {
                throw new ConnectorException("content is needed (text)", false);
            }
            byte[] data;
            if ("base64".equalsIgnoreCase(String.valueOf(inputs.get("encoding")))) {
                try {
                    data = Base64.getDecoder().decode(c);
                } catch (IllegalArgumentException e) {
                    throw new ConnectorException("content is not valid base64", false);
                }
            } else {
                data = c.getBytes(StandardCharsets.UTF_8);
            }
            if (data.length > Sftp.HARD_MAX_BYTES) {
                throw new ConnectorException("content is larger than " + Sftp.HARD_MAX_BYTES + " bytes", false);
            }
            return data;
        }

        @Override
        void validate(Map<String, Object> config, Map<String, Object> inputs) throws ConnectorException {
            Sftp.path(inputs.get("path"), false);
            bytes(inputs);
        }

        @Override
        Map<String, Object> run(Sftp.Session s, Map<String, Object> config, Map<String, Object> inputs) throws Exception {
            String path = Sftp.path(inputs.get("path"), false);
            byte[] data = bytes(inputs);
            try {
                s.write(path, data, Boolean.parseBoolean(String.valueOf(inputs.get("overwrite"))));
            } catch (java.nio.file.FileAlreadyExistsException e) {
                throw new ConnectorException("the file exists; set overwrite to replace it", false);
            }
            return Map.of("path", path, "size", (long) data.length);
        }
    }

    static final class Delete extends Base {
        Delete(Sftp sftp) {
            super(sftp);
        }

        @Override
        public List<ConnectorInput> inputs() {
            return List.of(ConnectorInput.required("path", "string", "File to delete (never a directory), relative to the account's home"));
        }

        @Override
        void validate(Map<String, Object> config, Map<String, Object> inputs) throws ConnectorException {
            Sftp.path(inputs.get("path"), false);
        }

        @Override
        Map<String, Object> run(Sftp.Session s, Map<String, Object> config, Map<String, Object> inputs) throws Exception {
            String path = Sftp.path(inputs.get("path"), false);
            s.delete(path);
            return Map.of("path", path, "deleted", true);
        }
    }
}
