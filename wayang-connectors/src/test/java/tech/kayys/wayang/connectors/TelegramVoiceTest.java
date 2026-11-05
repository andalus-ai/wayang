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

import tech.kayys.wayang.runtime.Poller;
import tech.kayys.wayang.runtime.ServiceHandler;

class TelegramVoiceTest {

    private static final String TOKEN = "123456789:ABCdefGhIJKlmNoPQRsTUVwxyZ0123456789";
    private static final byte[] OGG = {'O', 'g', 'g', 'S', 0, 2, 0, 0, 0, 0, 0, 0, 1, 2, 3};

    private HttpServer server;
    private final List<String> paths = new CopyOnWriteArrayList<>();
    private volatile byte[] fileBytes = OGG;
    private volatile String updates = "[]";
    private MediaStore store;
    private Telegram.Sender sender;

    @BeforeEach
    void start() throws Exception {
        store = new MediaStore(Files.createTempDirectory("tgvoice"));
        sender = new Telegram.Sender(new UrlGuard(true, Set.of()), store);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String p = ex.getRequestURI().getPath();
            paths.add(p);
            byte[] r;
            String type = "application/json";
            if (p.endsWith("/getFile")) {
                r = "{\"ok\":true,\"result\":{\"file_path\":\"voice/file_1.oga\",\"file_size\":15}}".getBytes(StandardCharsets.UTF_8);
            } else if (p.contains("/file/bot")) {
                r = fileBytes;
                type = "audio/ogg";
            } else {
                r = ("{\"ok\":true,\"result\":" + updates + "}").getBytes(StandardCharsets.UTF_8);
            }
            ex.getResponseHeaders().add("Content-Type", type);
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
        m.put("operation", "downloadFile");
        m.put("botToken", TOKEN);
        m.put("baseUrl", "http://127.0.0.1:" + server.getAddress().getPort());
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void aVoiceNoteIsFetchedIntoTheStoreAndReferencedById() throws Exception {
        Map<String, Object> out = sender.call(cfg(), Map.of("fileId", "AwACAgIAAxkBAAIB"));
        String id = (String) out.get("mediaId");
        assertTrue(id.matches("[0-9a-f]{32}\\.ogg"), id);
        assertEquals("audio/ogg", out.get("mimeType"));
        assertTrue(java.util.Arrays.equals(OGG, store.read(id)));
        assertEquals("/bot" + TOKEN + "/getFile", paths.get(0));
        assertEquals("/file/bot" + TOKEN + "/voice/file_1.oga", paths.get(1));
        assertFalse(out.toString().contains(TOKEN), "the token never leaves the connector");
    }

    @Test
    void whatIsNotAudioIsRefusedAndNothingIsStored() throws Exception {
        fileBytes = "<html>nope</html>".getBytes(StandardCharsets.UTF_8);
        assertThrows(ServiceHandler.ConnectorException.class, () -> sender.call(cfg(), Map.of("fileId", "AwACAgIAAxkBAAIB")));
    }

    @Test
    void fileIdsAreValidatedBeforeAnyRequest() {
        for (Object bad : new Object[] {"short", "../../etc/passwd", "a b c d e f g h i j", 42}) {
            assertThrows(ServiceHandler.ConnectorException.class, () -> sender.call(cfg(), Map.of("fileId", bad)));
        }
        assertTrue(paths.isEmpty(), paths.toString());
    }

    @Test
    void voiceNotesBecomeEventsOnlyWhenEnabledAndShortAndFromAnAllowedChat() throws Exception {
        updates = "[{\"update_id\":1,\"message\":{\"message_id\":5,\"date\":1,\"chat\":{\"id\":42,\"type\":\"private\"},\"voice\":{\"file_id\":\"AwACAgIAAxkBAAIB\",\"duration\":12}}},"
                + "{\"update_id\":2,\"message\":{\"message_id\":6,\"date\":1,\"chat\":{\"id\":42,\"type\":\"private\"},\"voice\":{\"file_id\":\"AwACAgIAAxkBAAIC\",\"duration\":900}}},"
                + "{\"update_id\":3,\"message\":{\"message_id\":7,\"date\":1,\"chat\":{\"id\":99,\"type\":\"private\"},\"voice\":{\"file_id\":\"AwACAgIAAxkBAAID\",\"duration\":5}}},"
                + "{\"update_id\":4,\"message\":{\"message_id\":8,\"date\":1,\"chat\":{\"id\":42,\"type\":\"private\"},\"text\":\"hi\"}}]";
        Telegram.Updates poller = new Telegram.Updates(new UrlGuard(true, Set.of()));
        Map<String, Object> on = cfg("allowedChatIds", List.of(42), "voice", true);
        Poller.Result r = poller.poll(on, null);
        assertEquals(2, r.events().size(), r.events().toString());
        assertEquals("AwACAgIAAxkBAAIB", r.events().get(0).get("voiceFileId"));
        assertEquals(12L, r.events().get(0).get("voiceSeconds"));
        assertEquals("", r.events().get(0).get("text"));
        assertEquals("hi", r.events().get(1).get("text"));
        assertNull(r.events().get(1).get("voiceFileId"));
        assertEquals("5", r.nextCursor());

        Poller.Result off = poller.poll(cfg("allowedChatIds", List.of(42)), null);
        assertEquals(1, off.events().size(), "without voice:true only text is an event");
    }
}
