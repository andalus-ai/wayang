package tech.kayys.wayang.multimodal;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import tech.kayys.wayang.connectors.MediaStore;
import tech.kayys.wayang.connectors.UrlGuard;
import tech.kayys.wayang.runtime.Extensions;
import tech.kayys.wayang.runtime.Profile;
import tech.kayys.wayang.runtime.ServiceHandler;

class MultimodalTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3, 4};

    private HttpServer server;
    private final List<String> seen = new CopyOnWriteArrayList<>();
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private final AtomicInteger historyCalls = new AtomicInteger();
    private volatile byte[] imageToServe = PNG;
    private Path dir;
    private MediaStore store;

    private void reply(HttpExchange ex, int status, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().add("Content-Type", type);
        ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            ex.getResponseBody().write(body);
        }
        ex.close();
    }

    @BeforeEach
    void start() throws IOException {
        dir = Files.createTempDirectory("media");
        store = new MediaStore(dir);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/images/generations", ex -> {
            seen.add(ex.getRequestMethod() + " " + ex.getRequestURI() + " auth=" + ex.getRequestHeaders().getFirst("Authorization"));
            bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String json = "{\"data\":[{\"b64_json\":\"" + Base64.getEncoder().encodeToString(imageToServe) + "\"}]}";
            reply(ex, 200, "application/json", json.getBytes(StandardCharsets.UTF_8));
        });
        server.createContext("/prompt", ex -> {
            seen.add(ex.getRequestMethod() + " " + ex.getRequestURI());
            bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            reply(ex, 200, "application/json", "{\"prompt_id\":\"abc-123\"}".getBytes(StandardCharsets.UTF_8));
        });
        server.createContext("/history/", ex -> {
            seen.add(ex.getRequestMethod() + " " + ex.getRequestURI());
            String out = historyCalls.incrementAndGet() < 2 ? "{}"
                    : "{\"abc-123\":{\"outputs\":{\"9\":{\"images\":[{\"filename\":\"out_00001_.png\",\"subfolder\":\"\",\"type\":\"output\"}]}}}}";
            reply(ex, 200, "application/json", out.getBytes(StandardCharsets.UTF_8));
        });
        server.createContext("/view", ex -> {
            seen.add(ex.getRequestMethod() + " " + ex.getRequestURI());
            reply(ex, 200, "image/png", imageToServe);
        });
        server.createContext("/v1/audio/transcriptions", ex -> {
            seen.add(ex.getRequestMethod() + " " + ex.getRequestURI() + " auth=" + ex.getRequestHeaders().getFirst("Authorization"));
            bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1));
            reply(ex, 200, "application/json", "{\"text\":\"  remind me to call Budi \"}".getBytes(StandardCharsets.UTF_8));
        });
        server.createContext("/v1/audio/speech", ex -> {
            seen.add(ex.getRequestMethod() + " " + ex.getRequestURI() + " auth=" + ex.getRequestHeaders().getFirst("Authorization"));
            bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            reply(ex, 200, "audio/ogg", OGG);
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private UrlGuard local() {
        return new UrlGuard(true, Set.of());
    }

    @Test
    void imagesAreStoredByReferenceAndTheKeyIsSentAsABearerToken() throws Exception {
        ImageGenerate c = new ImageGenerate(local(), store);
        Map<String, Object> out = c.call(Map.of("baseUrl", base() + "/v1", "model", "gpt-image-1", "apiKey", "sk-test"), Map.of("prompt", "a red kite"));
        assertEquals("image/png", out.get("mimeType"));
        assertEquals(PNG.length, out.get("bytes"));
        String id = (String) out.get("mediaId");
        assertTrue(id.matches("[0-9a-f]{32}\\.png"), id);
        assertTrue(java.util.Arrays.equals(PNG, store.read(id)));
        assertFalse(out.toString().contains(dir.toString()), "no filesystem path leaves the connector");
        assertTrue(seen.get(0).contains("auth=Bearer sk-test"), seen.toString());
        assertTrue(bodies.get(0).contains("\"prompt\":\"a red kite\"") && bodies.get(0).contains("b64_json"), bodies.get(0));
    }

    @Test
    void whatIsNotAnImageIsRefusedWhateverTheServerSays() {
        imageToServe = "<html>not an image</html>".getBytes(StandardCharsets.UTF_8);
        ImageGenerate c = new ImageGenerate(local(), store);
        assertThrows(ServiceHandler.ConnectorException.class,
                () -> c.call(Map.of("baseUrl", base() + "/v1", "model", "m"), Map.of("prompt", "x")));
        assertEquals(0, dir.toFile().list().length, "nothing was written");
    }

    @Test
    void inputsAreValidatedBeforeAnyRequest() {
        ImageGenerate c = new ImageGenerate(local(), store);
        for (Map<String, Object> in : List.<Map<String, Object>>of(Map.of(), Map.of("prompt", " "), Map.of("prompt", "x".repeat(4001)))) {
            assertThrows(ServiceHandler.ConnectorException.class, () -> c.call(Map.of("baseUrl", base() + "/v1", "model", "m"), in));
        }
        assertThrows(ServiceHandler.ConnectorException.class, () -> c.call(Map.of("baseUrl", base() + "/v1"), Map.of("prompt", "x")), "model required");
        assertThrows(ServiceHandler.ConnectorException.class, () -> c.call(Map.of("baseUrl", base() + "/v1", "model", "m", "size", "big"), Map.of("prompt", "x")));
        assertThrows(ServiceHandler.ConnectorException.class, () -> c.call(Map.of("baseUrl", base() + "/v1", "model", "m", "apiKey", "a b"), Map.of("prompt", "x")));
        assertTrue(seen.isEmpty(), seen.toString());
    }

    @Test
    void privateAddressesNeedTheOperatorToAllowThem() {
        ImageGenerate c = new ImageGenerate(UrlGuard.strict(), store);
        assertThrows(ServiceHandler.ConnectorException.class, () -> c.call(Map.of("baseUrl", base() + "/v1", "model", "m"), Map.of("prompt", "x")));
        assertTrue(seen.isEmpty());
    }

    @Test
    void comfyUiSubstitutesThePromptValueOnlyAndWaitsForTheImage() throws Exception {
        ComfyUi c = new ComfyUi(local(), store);
        Map<String, Object> wf = Map.of("6", Map.of("class_type", "CLIPTextEncode", "inputs", Map.of("text", "masterpiece, {{prompt}}")),
                "3", Map.of("class_type", "KSampler", "inputs", Map.of("seed", 5)));
        String evil = "x\"},\"7\":{\"class_type\":\"EvilNode\"";
        Map<String, Object> out = c.call(Map.of("baseUrl", base(), "workflow", wf, "timeoutSeconds", 10), Map.of("prompt", evil));
        assertEquals("abc-123", out.get("promptId"));
        assertTrue(java.util.Arrays.equals(PNG, store.read((String) out.get("mediaId"))));
        assertTrue(historyCalls.get() >= 2, "polled until the image existed");
        String sent = bodies.get(0);
        assertEquals(1, sent.split("EvilNode", -1).length - 1, "the text appears once, as a value");
        assertFalse(sent.contains("\"7\""), "no new node was injected: " + sent);
        assertTrue(sent.contains("masterpiece, x\\\""), sent);
        assertTrue(seen.stream().anyMatch(x -> x.startsWith("GET /view?filename=out_00001_.png&subfolder=&type=output")), seen.toString());
    }

    @Test
    void comfyUiOutputNamesCannotEscape() throws Exception {
        server.removeContext("/history/");
        server.createContext("/history/", ex -> reply(ex, 200, "application/json",
                "{\"abc-123\":{\"outputs\":{\"9\":{\"images\":[{\"filename\":\"../../etc/passwd\",\"subfolder\":\"\",\"type\":\"output\"}]}}}}".getBytes(StandardCharsets.UTF_8)));
        ComfyUi c = new ComfyUi(local(), store);
        ServiceHandler.ConnectorException e = assertThrows(ServiceHandler.ConnectorException.class,
                () -> c.call(Map.of("baseUrl", base(), "workflow", Map.of("1", Map.of("a", "b")), "timeoutSeconds", 5), Map.of("prompt", "x")));
        assertTrue(e.getMessage().contains("did not finish"), e.getMessage());
        assertTrue(seen.stream().noneMatch(x -> x.contains("/view")), "a hostile file name is never fetched");
    }

    @Test
    void mediaIdsAreNeverPaths() throws Exception {
        for (String bad : new String[] {"../x.png", "a.png", "0123456789abcdef0123456789abcdef.exe", "0123456789abcdef0123456789abcdef.png/../x", null}) {
            assertNull(store.read(bad), String.valueOf(bad));
        }
        assertThrows(ServiceHandler.ConnectorException.class, () -> new MediaStore(null).save(PNG));
        assertEquals("image/jpeg", MediaStore.sniff(new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0}));
        assertNull(MediaStore.sniff(new byte[] {1, 2, 3}));
    }

    @Test
    void theExtensionLoadsOnlyWhereTheProfileHasMedia() {
        assertEquals(List.of("multimodal"), Extensions.of(List.of(new MultimodalExtension()), Profile.FULL).ids());
        assertEquals(List.of("multimodal"), Extensions.of(List.of(new MultimodalExtension()), Profile.AGENTIC).ids());
        assertTrue(Extensions.of(List.of(new MultimodalExtension()), Profile.AUTOMATION).ids().isEmpty());
        assertTrue(Extensions.of(List.of(new MultimodalExtension()), Profile.INTEGRATION).ids().isEmpty());
        assertEquals(Set.of("image-generate", "comfyui", "transcribe", "speak"), new MultimodalExtension().connectors().keySet());
    }

    private static final byte[] OGG = {'O', 'g', 'g', 'S', 0, 2, 0, 0, 0, 0, 0, 0, 1, 2, 3};

    @Test
    void speechIsSentAsMultipartAndTheTranscriptIsTrimmed() throws Exception {
        String id = (String) store.saveAudio(OGG).get("mediaId");
        Transcribe t = new Transcribe(local(), store);
        Map<String, Object> out = t.call(Map.of("baseUrl", base() + "/v1", "apiKey", "sk-test", "language", "en"), Map.of("mediaId", id));
        assertEquals("remind me to call Budi", out.get("text"));
        assertTrue(seen.get(0).contains("auth=Bearer sk-test"), seen.toString());
        String body = bodies.get(0);
        assertTrue(body.contains("name=\"model\"\r\n\r\nwhisper-1") && body.contains("name=\"language\"\r\n\r\nen"), body);
        assertTrue(body.contains("filename=\"audio.ogg\"") && body.contains("Content-Type: audio/ogg") && body.contains("OggS"), body);
    }

    @Test
    void transcribeRefusesWhatIsNotStoredAudioBeforeAnyRequest() throws Exception {
        String img = (String) store.save(PNG).get("mediaId");
        Transcribe t = new Transcribe(local(), store);
        Map<String, Object> cfg = Map.of("baseUrl", base() + "/v1");
        for (Map<String, Object> in : List.<Map<String, Object>>of(Map.of(), Map.of("mediaId", "../../etc/passwd"), Map.of("mediaId", img),
                Map.of("mediaId", "0".repeat(32) + ".ogg"))) {
            assertThrows(ServiceHandler.ConnectorException.class, () -> t.call(cfg, in));
        }
        String id = (String) store.saveAudio(OGG).get("mediaId");
        assertThrows(ServiceHandler.ConnectorException.class, () -> t.call(Map.of("baseUrl", base() + "/v1", "language", "x;y"), Map.of("mediaId", id)));
        assertThrows(ServiceHandler.ConnectorException.class, () -> t.call(Map.of("baseUrl", base() + "/v1", "apiKey", "a b"), Map.of("mediaId", id)));
        assertTrue(seen.isEmpty(), seen.toString());
    }

    @Test
    void audioSniffingRecognisesCommonContainersOnly() {
        assertEquals("audio/ogg", MediaStore.sniffAudio(OGG));
        assertEquals("audio/mpeg", MediaStore.sniffAudio(new byte[] {'I', 'D', '3', 3, 0, 0, 0, 0, 0, 0}));
        assertEquals("audio/wav", MediaStore.sniffAudio("RIFF\0\0\0\0WAVEfmt ".getBytes(StandardCharsets.ISO_8859_1)));
        assertEquals("audio/mp4", MediaStore.sniffAudio(new byte[] {0, 0, 0, 0x20, 'f', 't', 'y', 'p', 'M', '4', 'A', ' '}));
        assertNull(MediaStore.sniffAudio(PNG));
        assertNull(MediaStore.sniffAudio("hello world, not audio".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void speechIsStoredAsAudioAndTheRequestCarriesTheChosenVoice() throws Exception {
        Speak s = new Speak(local(), store);
        Map<String, Object> out = s.call(Map.of("baseUrl", base() + "/v1", "apiKey", "sk-test", "voice", "nova"), Map.of("text", "Hello Budi"));
        assertEquals("audio/ogg", out.get("mimeType"));
        String id = (String) out.get("mediaId");
        assertTrue(id.matches("[0-9a-f]{32}\\.ogg"), id);
        assertTrue(java.util.Arrays.equals(OGG, store.read(id)));
        assertTrue(seen.get(0).contains("auth=Bearer sk-test"), seen.toString());
        String b = bodies.get(0);
        assertTrue(b.contains("\"voice\":\"nova\"") && b.contains("\"response_format\":\"opus\"") && b.contains("\"input\":\"Hello Budi\""), b);
    }

    @Test
    void speakRefusesBadInputBeforeAnyRequest() throws Exception {
        Speak s = new Speak(local(), store);
        Map<String, Object> cfg = Map.of("baseUrl", base() + "/v1");
        assertThrows(ServiceHandler.ConnectorException.class, () -> s.call(cfg, Map.of()));
        assertThrows(ServiceHandler.ConnectorException.class, () -> s.call(cfg, Map.of("text", "  ")));
        assertThrows(ServiceHandler.ConnectorException.class, () -> s.call(cfg, Map.of("text", "x".repeat(4001))));
        assertThrows(ServiceHandler.ConnectorException.class, () -> s.call(Map.of("baseUrl", base() + "/v1", "format", "flac"), Map.of("text", "hi")));
        assertThrows(ServiceHandler.ConnectorException.class, () -> s.call(Map.of("baseUrl", base() + "/v1", "voice", "a\"b"), Map.of("text", "hi")));
        assertThrows(ServiceHandler.ConnectorException.class, () -> s.call(Map.of("baseUrl", base() + "/v1", "apiKey", "a b"), Map.of("text", "hi")));
        assertTrue(seen.isEmpty(), seen.toString());
    }

    @Test
    void speakRefusesAnAnswerThatIsNotAudio() throws Exception {
        server.removeContext("/v1/audio/speech");
        server.createContext("/v1/audio/speech", ex -> reply(ex, 200, "text/plain", "oops".getBytes(StandardCharsets.UTF_8)));
        assertThrows(ServiceHandler.ConnectorException.class, () -> new Speak(local(), store).call(Map.of("baseUrl", base() + "/v1"), Map.of("text", "hi")));
    }
}
