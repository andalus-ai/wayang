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

/** Photos a person sends: the poller emits the largest acceptable size and downloadFile stores it as an image. */
class TelegramPhotoInputTest {

    private static final String TOKEN = "123456789:ABCdefGhIJKlmNoPQRsTUVwxyZ0123456789";
    private static final byte[] JPEG = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0, 0, 16, 'J', 'F', 'I', 'F', 0, 1};

    private HttpServer server;
    private final List<String> paths = new CopyOnWriteArrayList<>();
    private volatile byte[] fileBytes = JPEG;
    private volatile long reportedSize = JPEG.length;
    private volatile String updates = "[]";
    private MediaStore store;
    private Telegram.Sender sender;

    @BeforeEach
    void start() throws Exception {
        store = new MediaStore(Files.createTempDirectory("tgphotoin"));
        sender = new Telegram.Sender(new UrlGuard(true, Set.of()), store);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String p = ex.getRequestURI().getPath();
            paths.add(p);
            byte[] r;
            ex.getResponseHeaders().add("Content-Type", p.contains("/file/bot") ? "image/jpeg" : "application/json");
            if (p.endsWith("/getFile")) {
                r = ("{\"ok\":true,\"result\":{\"file_path\":\"photos/file_1.jpg\",\"file_size\":" + reportedSize + "}}").getBytes(StandardCharsets.UTF_8);
            } else if (p.contains("/file/bot")) {
                r = fileBytes;
            } else {
                r = ("{\"ok\":true,\"result\":" + updates + "}").getBytes(StandardCharsets.UTF_8);
            }
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
    void anImageIsStoredAsAnImageOnlyWhenAskedFor() throws Exception {
        Map<String, Object> out = sender.call(cfg(), Map.of("fileId", "AgACAgIAAxkBAAIB", "kind", "image"));
        assertTrue(((String) out.get("mediaId")).matches("[0-9a-f]{32}\\.jpg"), out.toString());
        assertEquals("image/jpeg", out.get("mimeType"));
        assertFalse(out.toString().contains(TOKEN));
        assertThrows(ServiceHandler.ConnectorException.class, () -> sender.call(cfg(), Map.of("fileId", "AgACAgIAAxkBAAIB")),
                "without kind the file must be audio, and a JPEG is not");
        assertThrows(ServiceHandler.ConnectorException.class, () -> sender.call(cfg(), Map.of("fileId", "AgACAgIAAxkBAAIB", "kind", "video")));
    }

    @Test
    void anOversizedOrNonImageDownloadIsRefused() {
        reportedSize = Telegram.MAX_PHOTO + 1;
        assertThrows(ServiceHandler.ConnectorException.class, () -> sender.call(cfg(), Map.of("fileId", "AgACAgIAAxkBAAIB", "kind", "image")));
        reportedSize = 5;
        fileBytes = "<html>no</html>".getBytes(StandardCharsets.UTF_8);
        assertThrows(ServiceHandler.ConnectorException.class, () -> sender.call(cfg(), Map.of("fileId", "AgACAgIAAxkBAAIB", "kind", "image")));
    }

    @Test
    void photosBecomeEventsWithTheLargestAcceptableSizeAndTheCaption() throws Exception {
        updates = "[{\"update_id\":1,\"message\":{\"message_id\":5,\"date\":1,\"chat\":{\"id\":42,\"type\":\"private\"},\"caption\":\"what is this?\",\"photo\":["
                + "{\"file_id\":\"small_AAAAAAAA\",\"width\":90,\"height\":60,\"file_size\":1000},"
                + "{\"file_id\":\"large_AAAAAAAA\",\"width\":1280,\"height\":960,\"file_size\":200000},"
                + "{\"file_id\":\"huge__AAAAAAAA\",\"width\":4000,\"height\":3000,\"file_size\":9000000}]}},"
                + "{\"update_id\":2,\"message\":{\"message_id\":6,\"date\":1,\"chat\":{\"id\":99,\"type\":\"private\"},\"photo\":[{\"file_id\":\"other_AAAAAAAA\",\"width\":9,\"height\":9}]}},"
                + "{\"update_id\":3,\"message\":{\"message_id\":7,\"date\":1,\"chat\":{\"id\":42,\"type\":\"private\"},\"photo\":[{\"file_id\":\"../etc\",\"width\":9,\"height\":9}]}}]";
        Telegram.Updates poller = new Telegram.Updates(new UrlGuard(true, Set.of()));
        Poller.Result on = poller.poll(cfg("allowedChatIds", List.of(42), "photo", true), null);
        assertEquals(1, on.events().size(), on.events().toString());
        assertEquals("large_AAAAAAAA", on.events().get(0).get("photoFileId"), "the huge size is over the limit");
        assertEquals("what is this?", on.events().get(0).get("text"));
        assertEquals("4", on.nextCursor());

        Poller.Result off = poller.poll(cfg("allowedChatIds", List.of(42)), null);
        assertTrue(off.events().isEmpty(), "without photo:true a photo is not an event");
    }

    @Test
    void aPhotoWithNoUsableSizeIsNotAnEvent() {
        assertNull(Telegram.largestPhoto(List.of()));
        assertNull(Telegram.largestPhoto(List.of(Map.of("file_id", "bad id", "width", 1, "height", 1))));
        assertNull(Telegram.largestPhoto(List.of("junk", Map.of("file_id", "valid_AAAAAAAA"))));
    }
}
