package tech.kayys.wayang.connectors;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import tech.kayys.wayang.runtime.ServiceHandler;

class TelegramPhotoTest {

    private static final String TOKEN = "123456789:ABCdefGhIJKlmNoPQRsTUVwxyZ0123456789";
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a, 7, 7, 7, 7};

    private HttpServer server;
    private final List<String> paths = new CopyOnWriteArrayList<>();
    private final List<String> types = new CopyOnWriteArrayList<>();
    private final List<byte[]> bodies = new CopyOnWriteArrayList<>();
    private MediaStore store;
    private Telegram.Sender sender;

    @BeforeEach
    void start() throws Exception {
        store = new MediaStore(Files.createTempDirectory("tgmedia"));
        sender = new Telegram.Sender(new UrlGuard(true, Set.of()), store);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            paths.add(ex.getRequestURI().getPath());
            types.add(String.valueOf(ex.getRequestHeaders().getFirst("Content-Type")));
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            ex.getRequestBody().transferTo(b);
            bodies.add(b.toByteArray());
            byte[] r = "{\"ok\":true,\"result\":{\"message_id\":77,\"chat\":{\"id\":42}}}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, r.length);
            ex.getResponseBody().write(r);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private Map<String, Object> cfg(Object... kv) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("operation", "sendPhoto");
        m.put("botToken", TOKEN);
        m.put("baseUrl", "http://127.0.0.1:" + server.getAddress().getPort());
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static boolean contains(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= hay.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    @Test
    void aStoredImageIsUploadedAsMultipartToTheConfiguredChat() throws Exception {
        String id = (String) store.save(PNG).get("mediaId");
        Map<String, Object> out = sender.call(cfg("chatId", 42), Map.of("mediaId", id, "caption", "Your kite"));
        assertEquals(77, ((Number) out.get("messageId")).intValue());
        assertEquals("/bot" + TOKEN + "/sendPhoto", paths.get(0));
        assertTrue(types.get(0).startsWith("multipart/form-data; boundary=wayang"), types.get(0));
        String text = new String(bodies.get(0), StandardCharsets.ISO_8859_1);
        assertTrue(text.contains("name=\"chat_id\"\r\n\r\n42\r\n") && text.contains("name=\"caption\"\r\n\r\nYour kite\r\n"), text);
        assertTrue(text.contains("filename=\"image.png\"") && text.contains("Content-Type: image/png"), text);
        assertTrue(contains(bodies.get(0), PNG), "the image bytes are in the body");
    }

    @Test
    void aFixedChatInTheConfigurationCannotBeOverriddenByTheModel() throws Exception {
        String id = (String) store.save(PNG).get("mediaId");
        sender.call(cfg("chatId", 42), Map.of("mediaId", id, "chatId", 987654321));
        String text = new String(bodies.get(0), StandardCharsets.ISO_8859_1);
        assertTrue(text.contains("\r\n\r\n42\r\n") && !text.contains("987654321"), text);
    }

    @Test
    void badInputsAreRefusedBeforeAnyRequest() throws Exception {
        String id = (String) store.save(PNG).get("mediaId");
        for (Map<String, Object> in : List.<Map<String, Object>>of(Map.of("chatId", 1), Map.of("mediaId", "../x.png", "chatId", 1),
                Map.of("mediaId", "0123456789abcdef0123456789abcdef.png", "chatId", 1), Map.of("mediaId", id),
                Map.of("mediaId", id, "chatId", "1\r\nx"), Map.of("mediaId", id, "chatId", 1, "caption", "c".repeat(1025)))) {
            assertThrows(ServiceHandler.ConnectorException.class, () -> sender.call(cfg(), in), in.toString());
        }
        assertTrue(paths.isEmpty(), paths.toString());
    }
}
