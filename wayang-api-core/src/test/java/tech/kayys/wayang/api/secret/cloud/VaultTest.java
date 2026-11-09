package tech.kayys.wayang.api.secret.cloud;

import tech.kayys.wayang.api.ApiException;
import tech.kayys.wayang.api.secret.InMemorySecretStore;
import tech.kayys.wayang.api.secret.Keyring;
import tech.kayys.wayang.api.Principal;
import tech.kayys.wayang.api.Role;
import tech.kayys.wayang.api.secret.SecretCipher;
import tech.kayys.wayang.api.secret.SecretService;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import tech.kayys.wayang.runtime.json.Json;

class VaultTest {

    private HttpServer server;
    private String addr;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final List<String> tokensSeen = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private volatile String body = "{\"data\":{\"data\":{\"value\":\"s3cret\",\"other\":\"x\",\"n\":5}}}";
    private volatile String loginBody = "{\"auth\":{\"client_token\":\"tok-1\",\"lease_duration\":100}}";
    private volatile int logins;
    private volatile String redirectTo;
    private Instant now = Instant.parse("2026-01-01T00:00:00Z");
    private final Clock clock = new Clock() {
        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId z) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    };

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        addr = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange ex) throws IOException {
        requests.add(ex.getRequestMethod() + " " + ex.getRequestURI().getRawPath());
        tokensSeen.add(String.valueOf(ex.getRequestHeaders().getFirst("X-Vault-Token")));
        byte[] out;
        int st = status;
        if (ex.getRequestURI().getPath().endsWith("/login")) {
            logins++;
            out = loginBody.getBytes(StandardCharsets.UTF_8);
            st = 200;
        } else if (redirectTo != null) {
            ex.getResponseHeaders().add("Location", redirectTo);
            st = 302;
            out = new byte[0];
        } else {
            out = body.getBytes(StandardCharsets.UTF_8);
        }
        ex.sendResponseHeaders(st, out.length == 0 ? -1 : out.length);
        if (out.length > 0) {
            ex.getResponseBody().write(out);
        }
        ex.close();
    }

    private VaultClient client() {
        return new VaultClient(addr, null, VaultClient.staticToken("root-token"), Duration.ofSeconds(5), false);
    }

    private VaultSecretResolver resolver(List<String> errors) {
        return new VaultSecretResolver(client(), "secret", "wayang", 30, 5, clock, errors::add);
    }

    // ------------------------------------------------------------ client

    @Test
    void readsAKv2SecretWithTheTokenInTheHeaderAndKeepsOnlyStringFields() {
        Map<String, String> m = client().readKv2("secret", "wayang/acme/db");
        assertEquals(Map.of("value", "s3cret", "other", "x"), m);
        assertEquals(List.of("GET /v1/secret/data/wayang/acme/db"), requests);
        assertEquals(List.of("root-token"), tokensSeen);
    }

    @Test
    void errorsNeverCarryTheTokenOrTheBody() {
        status = 403;
        body = "{\"errors\":[\"permission denied root-token s3cret\"]}";
        var e = assertThrows(VaultClient.VaultException.class, () -> client().readKv2("secret", "wayang/acme/db"));
        assertEquals(403, e.status());
        assertFalse(e.getMessage().contains("root-token") || e.getMessage().contains("s3cret"), e.getMessage());
        status = 404;
        assertEquals(404, assertThrows(VaultClient.VaultException.class, () -> client().readKv2("secret", "wayang/acme/db")).status());
    }

    @Test
    void redirectsAreNotFollowedSoTheTokenCannotBeSentElsewhere() {
        redirectTo = "http://127.0.0.1:1/steal";
        var e = assertThrows(VaultClient.VaultException.class, () -> client().readKv2("secret", "wayang/acme/db"));
        assertEquals(302, e.status());
        assertEquals(1, requests.size());
    }

    @Test
    void oversizedAndMalformedAnswersAreRefused() {
        body = "{\"data\":{\"data\":{\"value\":\"" + "x".repeat(VaultClient.MAX_RESPONSE_BYTES) + "\"}}}";
        assertTrue(assertThrows(VaultClient.VaultException.class, () -> client().readKv2("secret", "a/b")).getMessage().contains("larger"));
        body = "not json";
        assertTrue(assertThrows(VaultClient.VaultException.class, () -> client().readKv2("secret", "a/b")).getMessage().contains("invalid JSON"));
        body = "{\"data\":{}}";
        assertThrows(VaultClient.VaultException.class, () -> client().readKv2("secret", "a/b"));
    }

    @Test
    void pathsAreCheckedSegmentBySegment() {
        for (String bad : new String[] { "a/../b", "a//b", "../x", "a/b/", "a b/c", "a/b?x=1", "a/%2e%2e/b", "a/b#f", "" }) {
            assertThrows(IllegalArgumentException.class, () -> client().readKv2("secret", bad), bad);
        }
        assertThrows(IllegalArgumentException.class, () -> client().readKv2("sec/../ret", "a"));
        assertTrue(requests.isEmpty());
    }

    @Test
    void theAddressMustBeHttpsExceptForLoopbackOrWhenAllowed() {
        var t = VaultClient.staticToken("t");
        assertThrows(IllegalArgumentException.class, () -> new VaultClient("http://vault.example.com:8200", null, t, Duration.ofSeconds(1), false));
        assertThrows(IllegalArgumentException.class, () -> new VaultClient("ftp://vault.example.com", null, t, Duration.ofSeconds(1), false));
        assertThrows(IllegalArgumentException.class, () -> new VaultClient("https://u:p@vault.example.com", null, t, Duration.ofSeconds(1), false));
        assertThrows(IllegalArgumentException.class, () -> new VaultClient("https://vault.example.com?x=1", null, t, Duration.ofSeconds(1), false));
        new VaultClient("https://vault.example.com:8200/", null, t, Duration.ofSeconds(1), false);
        new VaultClient("http://localhost:8200", null, t, Duration.ofSeconds(1), false);
        new VaultClient("http://vault.internal:8200", null, t, Duration.ofSeconds(1), true);
    }

    @Test
    void aTokenWithANewlineIsRefusedAndAFileTokenIsReadEveryTime() throws IOException {
        Path dir = Files.createTempDirectory("wayang-vault-test");
        var bad = new VaultClient(addr, null, VaultClient.staticToken("a\nX-Evil: 1"), Duration.ofSeconds(1), false);
        assertThrows(VaultClient.VaultException.class, () -> bad.readKv2("secret", "a/b"));
        Path f = dir.resolve("token");
        Files.writeString(f, "first\n");
        var c = new VaultClient(addr, "team-a", VaultClient.tokenFile(f), Duration.ofSeconds(1), false);
        c.readKv2("secret", "a/b");
        Files.writeString(f, "second\n");
        c.readKv2("secret", "a/b");
        assertEquals(List.of("first", "second"), tokensSeen);
        Files.delete(f);
        assertThrows(VaultClient.VaultException.class, () -> c.readKv2("secret", "a/b"));
    }

    @Test
    void appRoleLogsInOnceRenewsAtEightyPercentOfTheLeaseAndSendsTheLoginBody() {
        var supplier = VaultClient.appRole(addr, null, "approle", "role-1", () -> "sec-1", Duration.ofSeconds(5), false, clock);
        assertEquals("tok-1", supplier.get());
        assertEquals("tok-1", supplier.get());
        assertEquals(1, logins);
        now = now.plusSeconds(79);
        supplier.get();
        assertEquals(1, logins);
        now = now.plusSeconds(2);
        loginBody = "{\"auth\":{\"client_token\":\"tok-2\",\"lease_duration\":100}}";
        assertEquals("tok-2", supplier.get());
        assertEquals(2, logins);
        assertEquals(List.of("POST /v1/auth/approle/login", "POST /v1/auth/approle/login"), requests);
        loginBody = "{\"auth\":{}}";
        now = now.plusSeconds(1000);
        assertTrue(assertThrows(VaultClient.VaultException.class, supplier::get).getMessage().contains("did not return a token"));
    }

    // ------------------------------------------------------------ resolver

    @Test
    void aVaultNameReadsTheTenantsOwnPathAndCachesTheAnswer() {
        List<String> errors = new java.util.ArrayList<>();
        var r = resolver(errors);
        assertEquals("s3cret", r.resolve("acme", "vault.db.password"));
        assertEquals("s3cret", r.resolve("acme", "vault.db.password"));
        assertEquals(List.of("GET /v1/secret/data/wayang/acme/db.password"), requests);
        assertEquals("s3cret", r.resolve("globex", "vault.db.password"));
        assertEquals("GET /v1/secret/data/wayang/globex/db.password", requests.get(1), "the tenant is in the path");
        now = now.plusSeconds(20);
        r.resolve("acme", "vault.db.password");
        assertEquals(2, requests.size(), "still cached well after the short miss ttl");
        now = now.plusSeconds(11);
        r.resolve("acme", "vault.db.password");
        assertEquals(3, requests.size(), "the cache expires");
        assertTrue(errors.isEmpty());
    }

    @Test
    void aMissingSecretIsNullAndRetriedAfterTheShortMissTtlOnly() {
        status = 404;
        List<String> errors = new java.util.ArrayList<>();
        var r = resolver(errors);
        assertNull(r.resolve("acme", "vault.nope"));
        assertNull(r.resolve("acme", "vault.nope"));
        assertEquals(1, requests.size());
        now = now.plusSeconds(6);
        status = 200;
        assertEquals("s3cret", r.resolve("acme", "vault.nope"));
        assertTrue(errors.isEmpty(), "a 404 is not an error worth logging");
    }

    @Test
    void failuresReadAsMissingAndAreLoggedWithoutSecrets() {
        status = 500;
        List<String> errors = new java.util.ArrayList<>();
        assertNull(resolver(errors).resolve("acme", "vault.x"));
        assertEquals(1, errors.size());
        assertFalse(errors.get(0).contains("root-token"));
    }

    @Test
    void nothingThatIsNotAPlainNameReachesVault() {
        var r = resolver(new java.util.ArrayList<>());
        for (String n : new String[] { "vault.", "vault..", "vault...", "vault.a..b", "vault./etc", "vault.a/b", "vault..x", "vault.a b", "vault.%2e", "db.password",
                null, "vault.x#field", "vault." + "a".repeat(101) }) {
            assertNull(r.resolve("acme", n), String.valueOf(n));
        }
        assertNull(r.resolve(null, "vault.x"));
        assertTrue(requests.isEmpty(), requests.toString());
    }

    @Test
    void emptyOrHugeValuesAndMissingFieldsAreNotSecrets() {
        var r = resolver(new java.util.ArrayList<>());
        body = "{\"data\":{\"data\":{\"value\":\"\"}}}";
        assertNull(r.resolve("acme", "vault.a"));
        body = "{\"data\":{\"data\":{\"other\":\"x\"}}}";
        assertNull(r.resolve("acme", "vault.b"));
        body = "{\"data\":{\"data\":{\"value\":\"" + "x".repeat(SecretService.MAX_VALUE_CHARS + 1) + "\"}}}";
        assertNull(r.resolve("acme", "vault.c"));
    }

    @Test
    void routingSendsOnlyVaultNamesToVault() {
        var r = VaultSecretResolver.routing((t, n) -> "local:" + n, resolver(new java.util.ArrayList<>()));
        assertEquals("local:TOKEN", r.resolve("acme", "TOKEN"));
        assertEquals("local:oauth.work", r.resolve("acme", "oauth.work"));
        assertEquals("s3cret", r.resolve("acme", "vault.k"));
    }

    @Test
    void secretsCannotBeStoredUnderAVaultName() {
        var svc = new SecretService(new InMemorySecretStore(), new SecretCipher(Map.of("k", new byte[32]), "k"), clock);
        var admin = new Principal("acme", "ann", java.util.Set.of(Role.ADMIN));
        assertThrows(ApiException.class, () -> svc.put(admin, "vault.shadow", "x"));
        svc.put(admin, "vaulty", "ok");
    }

    // ------------------------------------------------------------ keyring

    private static String b64(int fill) {
        byte[] k = new byte[32];
        java.util.Arrays.fill(k, (byte) fill);
        return Base64.getEncoder().encodeToString(k);
    }

    @Test
    void keyringParsesCommentsActiveAndSingleKeyDefault() {
        var kr = Keyring.parse("# keys\n\nactive=k2\nk1:" + b64(1) + "\n  k2 : " + b64(2) + "  \n");
        assertEquals("k2", kr.active());
        assertEquals(java.util.Set.of("k1", "k2"), kr.keys().keySet());
        assertEquals("k2", kr.cipher().activeKeyId());
        assertEquals("k1", Keyring.parse("k1:" + b64(1)).active());
    }

    @Test
    void keyringRejectsEverythingAmbiguousWithoutShowingKeyMaterial() {
        String k = b64(7);
        for (String bad : new String[] { "", "# only a comment", "k1:" + k + "\nk2:" + k, "active=k9\nk1:" + k, "k1:" + k + "\nk1:" + k,
                "k1:short", "k1:!!!notbase64", "bad id:" + k, "active=k1\nactive=k1\nk1:" + k, "nocolon", "k1:" + Base64.getEncoder().encodeToString(new byte[16]) }) {
            var e = assertThrows(IllegalArgumentException.class, () -> Keyring.parse(bad), bad);
            assertFalse(e.getMessage().contains(k), e.getMessage());
        }
        assertThrows(IllegalArgumentException.class, () -> Keyring.parse("x".repeat(70_000)));
    }

    @Test
    void keyringFileMustNotBeReadableByOthers() throws IOException {
        Path dir = Files.createTempDirectory("wayang-vault-test");
        Path f = dir.resolve("keys");
        Files.writeString(f, "k1:" + b64(3));
        Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rw-r--r--"));
        var e = assertThrows(IllegalStateException.class, () -> Keyring.fromFile(f, false));
        assertTrue(e.getMessage().contains("chmod 0400"));
        assertEquals("k1", Keyring.fromFile(f, true).active());
        Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("r--------"));
        assertEquals("k1", Keyring.fromFile(f, false).active());
        Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rw-rw----"));
        assertThrows(IllegalStateException.class, () -> Keyring.fromFile(f, false), "group-writable");
        assertThrows(IllegalStateException.class, () -> Keyring.fromFile(dir.resolve("missing"), true));
    }

    @Test
    void keyringFromVaultFeedsTheCipher() {
        body = Json.write(Map.of("data", Map.of("data", Map.of("active", "v2", "v1", b64(1), "v2", b64(2)))));
        Map<String, String> data = client().readKv2("secret", "wayang/keys");
        Map<String, java.util.function.Supplier<byte[]>> unused = Map.of();
        StringBuilder text = new StringBuilder("active=" + data.get("active") + "\n");
        data.forEach((k, v) -> {
            if (!k.equals("active")) {
                text.append(k).append(':').append(v).append('\n');
            }
        });
        assertEquals("v2", Keyring.parse(text.toString()).cipher().activeKeyId());
        assertTrue(unused.isEmpty());
    }
}
