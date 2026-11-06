package tech.kayys.wayang.connectors;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import tech.kayys.wayang.runtime.ServiceHandler;

class SlackUploadTest {

    // assembled at run time so secret scanners do not mistake this fake for a real token
    private static final String TOKEN = "xo" + "xb-" + "fake-test-token-for-unit-tests";
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 'I', 'H', 'D', 'R', 1, 2, 3, 4};

    private HttpServer server;
    private final List<String> seen = new CopyOnWriteArrayList<>();
    private volatile String completeReply = "{\"ok\":true}";
    private MediaStore store;
    private Slack.Sender sender;

    @BeforeEach
    void start() throws Exception {
        store = new MediaStore(Files.createTempDirectory("slackup"));
        sender = new Slack.Sender(new UrlGuard(true, Set.of()), store);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            byte[] body = ex.getRequestBody().readAllBytes();
            String p = ex.getRequestURI().getPath();
            String text = new String(body, StandardCharsets.ISO_8859_1);
            seen.add(p + " auth=" + ex.getRequestHeaders().getFirst("Authorization") + " type=" + ex.getRequestHeaders().getFirst("Content-Type")
                    + " len=" + body.length + (p.startsWith("/upload") ? "" : " " + text));
            String r;
            String type = "application/json";
            if (p.endsWith("files.getUploadURLExternal")) {
                r = "{\"ok\":true,\"upload_url\":\"http://127.0.0.1:" + server.getAddress().getPort() + "/upload/abc\",\"file_id\":\"F0123ABCD\"}";
            } else if (p.startsWith("/upload")) {
                r = "OK - " + body.length;
                type = "text/plain";
            } else {
                r = completeReply;
            }
            byte[] rb = r.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", type);
            ex.sendResponseHeaders(200, rb.length);
            ex.getResponseBody().write(rb);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private Map<String, Object> cfg(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("operation", "uploadFile");
        m.put("botToken", TOKEN);
        m.put("baseUrl", "http://127.0.0.1:" + server.getAddress().getPort());
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void anImageIsUploadedInThreeStepsAndTheTokenOnlyGoesToSlack() throws Exception {
        String id = (String) store.save(PNG).get("mediaId");
        var out = sender.call(cfg(), Map.of("channel", "C0123456789", "mediaId", id, "text", "Here you go", "threadTs", "1700000000.000100", "title", "Sunset"));
        assertEquals("F0123ABCD", out.get("fileId"));
        assertEquals("C0123456789", out.get("channel"));
        assertEquals(3, seen.size(), seen.toString());
        assertTrue(seen.get(0).startsWith("/api/files.getUploadURLExternal auth=Bearer " + TOKEN + " type=application/x-www-form-urlencoded")
                && seen.get(0).endsWith("filename=image.png&length=" + PNG.length), seen.get(0));
        assertEquals("/upload/abc auth=null type=image/png len=" + PNG.length, seen.get(1), "the upload host never sees the bot token");
        String done = seen.get(2);
        assertTrue(done.startsWith("/api/files.completeUploadExternal auth=Bearer " + TOKEN) && done.contains("\"channel_id\":\"C0123456789\"")
                && done.contains("\"initial_comment\":\"Here you go\"") && done.contains("\"thread_ts\":\"1700000000.000100\"") && done.contains("\"id\":\"F0123ABCD\"")
                && done.contains("\"title\":\"Sunset\""), done);
    }

    @Test
    void aFixedChannelInTheConfigWinsOverTheModelsChoice() throws Exception {
        String id = (String) store.save(PNG).get("mediaId");
        var out = sender.call(cfg("channel", "C0FIXED0001"), Map.of("channel", "C0OTHER0002", "mediaId", id));
        assertEquals("C0FIXED0001", out.get("channel"));
    }

    @Test
    void badInputsAreRefusedBeforeAnyRequest() throws Exception {
        String id = (String) store.save(PNG).get("mediaId");
        String audio = (String) store.saveAudio(new byte[] {'O', 'g', 'g', 'S', 0, 2, 0, 0, 0, 0, 0, 0, 1, 2, 3}).get("mediaId");
        for (Map<String, Object> in : List.<Map<String, Object>>of(Map.of("mediaId", id), Map.of("channel", "general", "mediaId", id),
                Map.of("channel", "C0123456789"), Map.of("channel", "C0123456789", "mediaId", "../../etc/passwd"),
                Map.of("channel", "C0123456789", "mediaId", audio), Map.of("channel", "C0123456789", "mediaId", id, "threadTs", "x; drop"),
                Map.of("channel", "C0123456789", "mediaId", id, "text", "x".repeat(4001)))) {
            assertThrows(ServiceHandler.ConnectorException.class, () -> sender.call(cfg(), in), in.toString());
        }
        assertThrows(ServiceHandler.ConnectorException.class, () -> sender.call(cfg("botToken", "nope"), Map.of("channel", "C0123456789", "mediaId", id)));
        assertThrows(ServiceHandler.ConnectorException.class, () -> sender.call(cfg("operation", "dropTable"), Map.of("channel", "C0123456789", "mediaId", id)));
        assertTrue(seen.isEmpty(), seen.toString());
    }

    @Test
    void slackErrorsSurfaceWithoutTheTokenAndRateLimitsAreRetryable() throws Exception {
        String id = (String) store.save(PNG).get("mediaId");
        completeReply = "{\"ok\":false,\"error\":\"not_in_channel\"}";
        var e = assertThrows(ServiceHandler.ConnectorException.class, () -> sender.call(cfg(), Map.of("channel", "C0123456789", "mediaId", id)));
        assertTrue(e.getMessage().contains("not_in_channel") && !e.getMessage().contains("xoxb") && !e.retryable(), e.getMessage());
        completeReply = "{\"ok\":false,\"error\":\"ratelimited\"}";
        var r = assertThrows(ServiceHandler.ConnectorException.class, () -> sender.call(cfg(), Map.of("channel", "C0123456789", "mediaId", id)));
        assertTrue(r.retryable());
    }

    @Test
    void plainPostMessageStillWorksWithAnExplicitOperationName() throws Exception {
        completeReply = "{\"ok\":true,\"ts\":\"1.2\",\"channel\":\"C1\"}";
        var out = sender.call(cfg("operation", "postMessage"), Map.of("channel", "C0123456789", "text", "hi"));
        assertEquals("1.2", out.get("ts"));
        assertTrue(seen.get(0).startsWith("/api/chat.postMessage"), seen.get(0));
    }
}
