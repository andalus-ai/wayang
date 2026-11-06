package tech.kayys.wayang.connectors;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
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

class TelegramSendVoiceTest {

    private static final String TOKEN = "123456789:ABCdefGhIJKlmNoPQRsTUVwxyZ0123456789";
    private static final byte[] OGG = {'O', 'g', 'g', 'S', 0, 2, 0, 0, 0, 0, 0, 0, 5, 5};
    private static final byte[] MP3 = {'I', 'D', '3', 3, 0, 0, 0, 0, 0, 0, 9, 9};

    private HttpServer server;
    private final List<String> paths = new CopyOnWriteArrayList<>();
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private MediaStore store;
    private Telegram.Sender sender;

    @BeforeEach
    void start() throws Exception {
        store = new MediaStore(Files.createTempDirectory("tgvoice"));
        sender = new Telegram.Sender(new UrlGuard(true, Set.of()), store);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            paths.add(ex.getRequestURI().getPath());
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            ex.getRequestBody().transferTo(b);
            bodies.add(new String(b.toByteArray(), StandardCharsets.ISO_8859_1));
            byte[] r = "{\"ok\":true,\"result\":{\"message_id\":88}}".getBytes(StandardCharsets.UTF_8);
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
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("operation", "sendVoice");
        m.put("botToken", TOKEN);
        m.put("baseUrl", "http://127.0.0.1:" + server.getAddress().getPort());
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void oggIsSentAsAVoiceNoteToTheFixedChat() throws Exception {
        String id = (String) store.saveAudio(OGG).get("mediaId");
        Map<String, Object> out = sender.call(cfg("chatId", 42), Map.of("mediaId", id, "chatId", 987654321));
        assertEquals(88, ((Number) out.get("messageId")).intValue());
        assertEquals("/bot" + TOKEN + "/sendVoice", paths.get(0));
        String b = bodies.get(0);
        assertTrue(b.contains("name=\"voice\"; filename=\"audio.ogg\"") && b.contains("OggS") && b.contains("name=\"chat_id\"\r\n\r\n42\r\n") && !b.contains("987654321"), b);
    }

    @Test
    void otherAudioIsSentAsAnAudioFile() throws Exception {
        String id = (String) store.saveAudio(MP3).get("mediaId");
        sender.call(cfg("chatId", 42), Map.of("mediaId", id));
        assertEquals("/bot" + TOKEN + "/sendAudio", paths.get(0));
        assertTrue(bodies.get(0).contains("name=\"audio\"; filename=\"audio.mp3\""), bodies.get(0));
    }

    @Test
    void badInputsAreRefusedBeforeAnyRequest() throws Exception {
        String img = (String) store.save(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a, 1, 2}).get("mediaId");
        String id = (String) store.saveAudio(OGG).get("mediaId");
        for (Map<String, Object> in : List.<Map<String, Object>>of(Map.of("chatId", 1), Map.of("mediaId", img, "chatId", 1), Map.of("mediaId", "../x.ogg", "chatId", 1),
                Map.of("mediaId", id), Map.of("mediaId", id, "chatId", "1\r\nx"))) {
            assertThrows(ServiceHandler.ConnectorException.class, () -> sender.call(cfg(), in), in.toString());
        }
        assertTrue(paths.isEmpty(), paths.toString());
    }
}
