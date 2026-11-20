package tech.kayys.wayang.embed;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tech.kayys.wayang.runtime.ServiceHandler;
import tech.kayys.wayang.runtime.json.Json;

/** The release's file API goes through its own file-* connectors, for the release tenant only. */
class FileApiTest {

    private final List<String> tenants = new ArrayList<>();
    private final Map<String, byte[]> blobs = new LinkedHashMap<>();
    private final Map<String, String> names = new LinkedHashMap<>();

    private abstract class Fake implements ServiceHandler.Connector {
        @Override
        public Map<String, Object> call(Map<String, Object> in) {
            throw new IllegalStateException("needs config");
        }

        @Override
        public boolean tenantScoped() {
            return true;
        }
    }

    private final Map<String, ServiceHandler.Connector> files = Map.of(
            "file-put", new Fake() {
                @Override
                public Map<String, Object> call(Map<String, Object> c, Map<String, Object> in) {
                    tenants.add(String.valueOf(c.get("wayang.tenant")));
                    String id = String.format("%024x", blobs.size() + 1);
                    blobs.put(id, Base64.getDecoder().decode((String) in.get("contentBase64")));
                    names.put(id, (String) in.get("fileName"));
                    return Map.of("stored", true, "fileId", id, "fileName", in.get("fileName"), "size", blobs.get(id).length,
                            "ttl", String.valueOf(in.get("ttlDays")));
                }
            },
            "file-get", new Fake() {
                @Override
                public Map<String, Object> call(Map<String, Object> c, Map<String, Object> in) {
                    tenants.add(String.valueOf(c.get("wayang.tenant")));
                    byte[] b = blobs.get(in.get("fileId"));
                    if (b == null) {
                        return Map.of("found", false);
                    }
                    return Map.of("found", true, "fileName", names.get(in.get("fileId")), "contentBase64", Base64.getEncoder().encodeToString(b));
                }
            },
            "file-list", new Fake() {
                @Override
                public Map<String, Object> call(Map<String, Object> c, Map<String, Object> in) {
                    return Map.of("files", new ArrayList<>(blobs.keySet()), "count", blobs.size());
                }
            },
            "file-delete", new Fake() {
                @Override
                public Map<String, Object> call(Map<String, Object> c, Map<String, Object> in) {
                    return Map.of("deleted", blobs.remove(in.get("fileId")) != null);
                }
            });

    private static String bundle() {
        var m = tech.kayys.wayang.bpmn.ProcessModel.builder("p").name("P").start("s").end("e").flow("s", "e").build();
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("format", "wayang.workspace");
        b.put("formatVersion", 1);
        b.put("project", Map.of("name", "Demo"));
        b.put("definitions", List.of(Map.of("kind", "PROCESS", "id", "p", "publishedVersion", 1, "document", tech.kayys.wayang.bpmn.ProcessMaps.toMap(m))));
        b.put("config", Map.of());
        b.put("requires", Map.of("secrets", List.of(), "externalSecrets", List.of(), "oauthConnections", List.of(), "config", List.of()));
        b.put("integrity", Map.of("sha256", "abc123"));
        return Json.write(b);
    }

    private static EmbeddedRuntime runtime(Map<String, ServiceHandler.Connector> connectors) {
        return new EmbeddedRuntime(ReleaseBundle.parse(bundle()), connectors, null, null, null, Clock.systemUTC());
    }

    private static HttpResponse<byte[]> send(HttpService s, String method, String path, byte[] body, boolean auth) throws Exception {
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + s.port() + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        if (auth) {
            rb.header("Authorization", "Bearer sekret");
        }
        return HttpClient.newHttpClient().send(rb.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> json(HttpResponse<byte[]> r) {
        return (Map<String, Object>) Json.read(new String(r.body(), java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void uploadListDownloadAndDeleteForTheReleaseTenant() throws Exception {
        try (var s = new HttpService(runtime(files), "sekret", new InetSocketAddress("127.0.0.1", 0), 4096).start()) {
            byte[] data = {0, 1, 2, (byte) 250, 'p', 'd', 'f'};
            var up = send(s, "POST", "/v1/files?name=Contract%20v2.pdf&ttlDays=30", data, true);
            assertEquals(201, up.statusCode(), new String(up.body()));
            String id = (String) json(up).get("fileId");
            assertEquals("30", json(up).get("ttl"));
            assertEquals(List.of("release"), tenants, "the tenant is the release's, set by the server");

            assertEquals(List.of(id), json(send(s, "GET", "/v1/files", null, true)).get("files"));

            var down = send(s, "GET", "/v1/files/" + id, null, true);
            assertEquals(200, down.statusCode());
            assertTrue(java.util.Arrays.equals(data, down.body()), "the bytes come back unchanged");
            assertEquals("application/octet-stream", down.headers().firstValue("Content-Type").orElse(""));
            assertEquals("attachment; filename=\"Contract_v2.pdf\"", down.headers().firstValue("Content-Disposition").orElse(""));
            assertEquals("nosniff", down.headers().firstValue("X-Content-Type-Options").orElse(""));

            assertEquals(200, send(s, "DELETE", "/v1/files/" + id, null, true).statusCode());
            assertEquals(404, send(s, "GET", "/v1/files/" + id, null, true).statusCode());
            assertEquals(404, send(s, "DELETE", "/v1/files/" + id, null, true).statusCode());
        }
    }

    @Test
    void theApiKeyIsRequiredAndBadRequestsAreRefused() throws Exception {
        try (var s = new HttpService(runtime(files), "sekret", new InetSocketAddress("127.0.0.1", 0), 4096).start()) {
            assertEquals(401, send(s, "POST", "/v1/files?name=a.txt", new byte[] {1}, false).statusCode());
            assertEquals(401, send(s, "GET", "/v1/files", null, false).statusCode());
            assertEquals(400, send(s, "POST", "/v1/files", new byte[] {1}, true).statusCode(), "no name");
            assertEquals(400, send(s, "POST", "/v1/files?name=a.txt", new byte[0], true).statusCode(), "empty body");
            assertEquals(413, send(s, "POST", "/v1/files?name=big.bin", new byte[(8 << 20) + 1], true).statusCode());
            assertEquals(404, send(s, "GET", "/v1/files/..%2F..%2Fetc", null, true).statusCode(), "not an id");
            assertEquals(404, send(s, "GET", "/v1/files/ABC", null, true).statusCode());
            assertEquals(405, send(s, "PUT", "/v1/files", new byte[] {1}, true).statusCode());
            assertEquals(405, send(s, "POST", "/v1/files/" + "0".repeat(24), new byte[] {1}, true).statusCode());
            assertTrue(blobs.isEmpty(), "nothing was stored by a refused request");
        }
    }

    @Test
    void aReleaseWithoutFileStorageSaysSo() throws Exception {
        try (var s = new HttpService(runtime(Map.of()), "sekret", new InetSocketAddress("127.0.0.1", 0), 4096).start()) {
            var r = send(s, "POST", "/v1/files?name=a.txt", new byte[] {1}, true);
            assertEquals(404, r.statusCode());
            assertTrue(json(r).get("error").toString().contains("no file storage"));
        }
    }

    @Test
    void aConnectorFailureIsAClearErrorWithoutDetails() throws Exception {
        var failing = Map.<String, ServiceHandler.Connector>of("file-put", new Fake() {
            @Override
            public Map<String, Object> call(Map<String, Object> c, Map<String, Object> in) throws Exception {
                throw new ServiceHandler.ConnectorException("file storage is not configured: set WAYANG_FILES_DIR", false);
            }
        });
        try (var s = new HttpService(runtime(failing), "sekret", new InetSocketAddress("127.0.0.1", 0), 4096).start()) {
            var r = send(s, "POST", "/v1/files?name=a.txt", new byte[] {1}, true);
            assertEquals(400, r.statusCode());
            assertTrue(json(r).get("error").toString().contains("WAYANG_FILES_DIR"));
        }
    }
}
