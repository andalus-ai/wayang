package tech.kayys.wayang.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tech.kayys.wayang.connectors.UrlGuard;
import tech.kayys.wayang.runtime.ServiceHandler;

class FileFetchTest {

    private HttpServer server;
    private String base;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/docs/contract.txt", x -> {
            byte[] b = "ocelot clause text".getBytes(StandardCharsets.UTF_8);
            x.sendResponseHeaders(200, b.length);
            x.getResponseBody().write(b);
            x.close();
        });
        server.createContext("/empty", x -> {
            x.sendResponseHeaders(200, -1);
            x.close();
        });
        server.createContext("/moved", x -> {
            x.getResponseHeaders().add("Location", "http://127.0.0.1/elsewhere");
            x.sendResponseHeaders(302, -1);
            x.close();
        });
        server.createContext("/big", x -> {
            x.sendResponseHeaders(200, 0);
            byte[] chunk = new byte[1 << 20];
            for (int i = 0; i < 21; i++) {
                x.getResponseBody().write(chunk);
            }
            x.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private KnowledgeExtension ext(boolean allowPrivate) throws Exception {
        return new KnowledgeExtension(new FileStore(Files.createTempDirectory("wayang-fetch"), java.time.Clock.systemUTC(), 0, 0),
                new UrlGuard(allowPrivate, Set.of()));
    }

    private static final Map<String, Object> CFG = Map.of("wayang.tenant", "t");

    @Test
    void fetchesAStoresItAndIngestCanUseTheFileId() throws Exception {
        KnowledgeExtension e = ext(true);
        Map<String, Object> out = e.connectors().get("file-fetch").call(CFG, Map.of("url", base + "/docs/contract.txt", "ttlDays", 5));
        assertEquals(true, out.get("stored"));
        assertEquals("contract.txt", out.get("fileName"), "named from the URL");
        assertEquals(18L, out.get("size"));
        assertTrue(out.get("expiresAt") != null);
        Map<String, Object> ing = e.connectors().get("rag-ingest").call(Map.of("knowledge", "k", "wayang.tenant", "t"),
                Map.of("documents", List.of(Map.of("fileId", out.get("fileId")))));
        assertEquals(1, ing.get("ingested"), ing.toString());
        assertEquals(1, ((List<?>) e.connectors().get("rag-search").call(Map.of("knowledge", "k", "wayang.tenant", "t"), Map.of("query", "ocelot")).get("hits")).size());
    }

    @Test
    void anExplicitNameWinsAndMissingUrlIsReportedNotFatal() throws Exception {
        KnowledgeExtension e = ext(true);
        assertEquals("mine.txt", e.connectors().get("file-fetch").call(CFG, Map.of("url", base + "/docs/contract.txt", "fileName", "mine.txt")).get("fileName"));
        Map<String, Object> none = e.connectors().get("file-fetch").call(CFG, Map.of());
        assertEquals(false, none.get("stored"));
        assertEquals("no url", none.get("reason"));
        assertEquals(false, e.connectors().get("file-fetch").call(CFG, Map.of("url", base + "/empty")).get("stored"));
    }

    @Test
    void privateAddressesAreRefusedByDefaultAndRedirectsAndHugeAnswersFail() throws Exception {
        assertThrows(ServiceHandler.ConnectorException.class, () -> ext(false).connectors().get("file-fetch").call(CFG, Map.of("url", base + "/docs/contract.txt")));
        KnowledgeExtension e = ext(true);
        ServiceHandler.ConnectorException r = assertThrows(ServiceHandler.ConnectorException.class,
                () -> e.connectors().get("file-fetch").call(CFG, Map.of("url", base + "/moved")));
        assertTrue(r.getMessage().contains("302"), r.getMessage());
        ServiceHandler.ConnectorException b = assertThrows(ServiceHandler.ConnectorException.class,
                () -> e.connectors().get("file-fetch").call(CFG, Map.of("url", base + "/big")));
        assertTrue(b.getMessage().contains("larger"), b.getMessage());
        assertThrows(ServiceHandler.ConnectorException.class, () -> e.connectors().get("file-fetch").call(CFG, Map.of("url", "file:///etc/passwd")));
        assertFalse(((List<?>) e.connectors().get("file-list").call(CFG, Map.of()).get("files")).size() > 0);
    }

    @Test
    void headersAreSentOnlyUnderTheUrlPrefixAndNeverEchoed() throws Exception {
        KnowledgeExtension e = ext(true);
        Map<String, Object> cfg = Map.of("wayang.tenant", "t", "urlPrefix", base + "/private", "headers", Map.of("Authorization", "Bearer s3cret"));
        assertEquals(true, e.connectors().get("file-fetch").call(cfg, Map.of("url", base + "/private/a.txt")).get("stored"));
        // outside the prefix (other path, traversal, other host): refused before any request, and the secret is not in the message
        for (String u : new String[] {base + "/docs/contract.txt", base + "/private/../docs/contract.txt", base + "/privateX/a.txt",
                "http://localhost:" + server.getAddress().getPort() + "/private/a.txt"}) {
            ServiceHandler.ConnectorException x = assertThrows(ServiceHandler.ConnectorException.class,
                    () -> e.connectors().get("file-fetch").call(cfg, Map.of("url", u)), u);
            assertTrue(x.getMessage().contains("outside urlPrefix"), x.getMessage());
            assertFalse(x.getMessage().contains("s3cret"));
        }
        // headers without a prefix, and an invalid header, are configuration errors
        ServiceHandler.ConnectorException n = assertThrows(ServiceHandler.ConnectorException.class,
                () -> e.connectors().get("file-fetch").call(Map.of("wayang.tenant", "t", "headers", Map.of("Authorization", "x")), Map.of("url", base + "/private/a.txt")));
        assertTrue(n.getMessage().contains("urlPrefix"));
        ServiceHandler.ConnectorException bad = assertThrows(ServiceHandler.ConnectorException.class, () -> e.connectors().get("file-fetch").call(
                Map.of("wayang.tenant", "t", "urlPrefix", base, "headers", Map.of("X-A", "v\r\nInjected: 1")), Map.of("url", base + "/private/a.txt")));
        assertFalse(bad.getMessage().contains("Injected"));
        // without headers the private path answers 401
        assertThrows(ServiceHandler.ConnectorException.class, () -> e.connectors().get("file-fetch").call(CFG, Map.of("url", base + "/private/a.txt")));
    }
}
