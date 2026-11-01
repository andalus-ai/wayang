package tech.kayys.wayang.api;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import tech.kayys.wayang.runtime.SecretResolver;
import tech.kayys.wayang.runtime.json.Json;

class CloudSecretTest {

    private HttpServer server;
    private URI addr;
    private final List<String> seen = new CopyOnWriteArrayList<>();
    private final List<Map<String, String>> headersSeen = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private volatile String body = "{}";
    private volatile int tokenCalls;
    private volatile String tokenBody = "{\"access_token\":\"tok-1\",\"expires_in\":3600}";
    private volatile String lastForm = "";
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
    void up() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        addr = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void down() {
        server.stop(0);
    }

    private void handle(HttpExchange ex) throws IOException {
        byte[] in = ex.getRequestBody().readAllBytes();
        String path = ex.getRequestURI().getRawPath();
        seen.add(ex.getRequestMethod() + " " + ex.getRequestURI().getRawPath() + (ex.getRequestURI().getRawQuery() == null ? "" : "?" + ex.getRequestURI().getRawQuery())
                + " " + new String(in, StandardCharsets.UTF_8));
        Map<String, String> h = new java.util.HashMap<>();
        ex.getRequestHeaders().forEach((k, v) -> h.put(k.toLowerCase(), v.get(0)));
        headersSeen.add(h);
        String out = body;
        int st = status;
        if (path.endsWith("/token")) {
            tokenCalls++;
            lastForm = new String(in, StandardCharsets.UTF_8);
            out = tokenBody;
            st = 200;
        }
        byte[] b = out.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(st, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    private final CloudHttp http = new CloudHttp(Duration.ofSeconds(5));

    // ------------------------------------------------------------------ SigV4

    @Test
    void sigV4MatchesTheAwsPublishedGetVanillaVector() {
        var c = new AwsSigV4.Credentials("AKIDEXAMPLE", "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY", null);
        Map<String, String> out = AwsSigV4.sign("GET", URI.create("https://example.amazonaws.com/"), Map.of(), null, "us-east-1", "service", c,
                Instant.parse("2015-08-30T12:36:00Z"));
        assertEquals("AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20150830/us-east-1/service/aws4_request, SignedHeaders=host;x-amz-date, "
                + "Signature=5fa00fa31553b73ebf1942676e86291e8372ff2a2260956d9b8aae1d763fbf31", out.get("authorization"));
        assertEquals("20150830T123600Z", out.get("x-amz-date"));
    }

    @Test
    void sigV4SignsTheSessionTokenAndExtraHeadersAndTheBody() {
        var c = new AwsSigV4.Credentials("AK", "SK", "TOKEN");
        Map<String, String> a = AwsSigV4.sign("POST", URI.create("https://x.example.com/"), Map.of("X-Amz-Target", "t"), "{}".getBytes(), "eu-west-1", "svc", c, now);
        assertEquals("TOKEN", a.get("x-amz-security-token"));
        assertTrue(a.get("authorization").contains("SignedHeaders=host;x-amz-date;x-amz-security-token;x-amz-target"));
        Map<String, String> b = AwsSigV4.sign("POST", URI.create("https://x.example.com/"), Map.of("X-Amz-Target", "t"), "{ }".getBytes(), "eu-west-1", "svc", c, now);
        assertTrue(!a.get("authorization").equals(b.get("authorization")), "the body is part of the signature");
        Map<String, String> d = AwsSigV4.sign("POST", URI.create("https://x.example.com/"), Map.of("X-Amz-Target", "t"), "{}".getBytes(), "eu-west-1", "svc",
                new AwsSigV4.Credentials("AK", "OTHER", "TOKEN"), now);
        assertTrue(!a.get("authorization").equals(d.get("authorization")), "the secret key is part of the signature");
        assertFalse(c.toString().contains("SK"));
    }

    // -------------------------------------------------------------------- AWS

    private AwsSecretsManager aws(Supplier<AwsSigV4.Credentials> creds) {
        return new AwsSecretsManager("eu-west-1", addr, "wayang", creds, http, clock);
    }

    @Test
    void awsReadsThePerTenantSecretString() {
        body = "{\"SecretString\":\"hunter2\",\"Name\":\"x\"}";
        assertEquals("hunter2", aws(() -> new AwsSigV4.Credentials("AK", "SK", null)).fetch("acme", "API_KEY"));
        assertTrue(seen.get(0).startsWith("POST / "));
        assertTrue(seen.get(0).contains("\"SecretId\":\"wayang/acme/API_KEY\""));
        Map<String, String> h = headersSeen.get(0);
        assertEquals("secretsmanager.GetSecretValue", h.get("x-amz-target"));
        assertTrue(h.get("authorization").startsWith("AWS4-HMAC-SHA256 Credential=AK/20260101/eu-west-1/secretsmanager/aws4_request"));
        assertEquals("20260101T000000Z", h.get("x-amz-date"));
        assertFalse(h.get("authorization").contains("SK"));
    }

    @Test
    void awsNotFoundIsNullButOtherErrorsAreErrorsWithoutBodies() {
        status = 400;
        body = "{\"__type\":\"ResourceNotFoundException\",\"Message\":\"Secrets Manager can't find the specified secret.\"}";
        assertNull(aws(() -> new AwsSigV4.Credentials("AK", "SK", null)).fetch("acme", "X"));
        body = "{\"__type\":\"com.amazon.coral#AccessDeniedException\",\"Message\":\"User arn:aws:iam::123:user/x is not authorized\"}";
        var e = assertThrows(CloudException.class, () -> aws(() -> new AwsSigV4.Credentials("AK", "SK", null)).fetch("acme", "X"));
        assertEquals("AWS Secrets Manager answered 400 AccessDeniedException", e.getMessage());
        assertFalse(e.getMessage().contains("arn:"));
        status = 500;
        body = "<html>oops SK</html>";
        var e2 = assertThrows(CloudException.class, () -> aws(() -> new AwsSigV4.Credentials("AK", "SK", null)).fetch("acme", "X"));
        assertEquals("AWS Secrets Manager answered 500", e2.getMessage());
    }

    @Test
    void awsRefusesMissingCredentialsAndBadNamesBeforeAnyRequest() {
        assertThrows(CloudException.class, () -> aws(() -> null).fetch("acme", "X"));
        assertThrows(CloudException.class, () -> aws(() -> new AwsSigV4.Credentials("AK", " ", null)).fetch("acme", "X"));
        assertThrows(IllegalArgumentException.class, () -> aws(() -> new AwsSigV4.Credentials("AK", "SK", null)).fetch("acme", "../x"));
        assertThrows(IllegalArgumentException.class, () -> aws(() -> new AwsSigV4.Credentials("AK", "SK", null)).fetch("a/b", "x"));
        assertThrows(IllegalArgumentException.class, () -> new AwsSecretsManager("nowhere", addr, "w", () -> null, http, clock));
        assertThrows(IllegalArgumentException.class, () -> new AwsSecretsManager("eu-west-1", addr, "w/../x y", () -> null, http, clock));
        assertTrue(seen.isEmpty());
    }

    @Test
    void awsBinarySecretsAndGarbageAreNotValues() {
        body = "{\"SecretBinary\":\"AAEC\"}";
        assertNull(aws(() -> new AwsSigV4.Credentials("AK", "SK", null)).fetch("acme", "X"));
        body = "not json";
        assertThrows(CloudException.class, () -> aws(() -> new AwsSigV4.Credentials("AK", "SK", null)).fetch("acme", "X"));
    }

    // ------------------------------------------------------------------ Azure

    @Test
    void azureGetsATokenOnceAndReadsTheFlatTenantScopedName() {
        body = "{\"value\":\"az-secret\",\"id\":\"https://v/secrets/x\"}";
        var tok = CloudTokens.azureClientCredentials(addr, "tenant-1", "client-1", () -> "cs&/=+ secret\n", http, clock);
        var kv = new AzureKeyVault(addr, "wayang", tok, http);
        assertEquals("az-secret", kv.fetch("acme", "api-key"));
        assertEquals("az-secret", kv.fetch("acme", "api-key"));
        assertEquals(1, tokenCalls, "the token is cached");
        assertTrue(lastForm.contains("grant_type=client_credentials") && lastForm.contains("client_id=client-1"));
        assertTrue(lastForm.contains("client_secret=cs%26%2F%3D%2B+secret"), lastForm);
        assertTrue(lastForm.contains("scope=https%3A%2F%2Fvault.azure.net%2F.default"));
        String get = seen.stream().filter(s -> s.startsWith("GET")).findFirst().orElseThrow();
        assertTrue(get.startsWith("GET /secrets/wayang--acme--api-key?api-version=7.4"), get);
        assertEquals("Bearer tok-1", headersSeen.stream().filter(h -> h.containsKey("authorization")).findFirst().orElseThrow().get("authorization"));
        now = now.plusSeconds(3600);
        kv.fetch("acme", "api-key");
        assertEquals(2, tokenCalls, "refreshed once it is about to expire");
    }

    @Test
    void aTokenIsRefreshedAMinuteBeforeItExpires() {
        tokenBody = "{\"access_token\":\"short\",\"expires_in\":100}";
        var tok = CloudTokens.azureClientCredentials(addr, "t", "c", () -> "s", http, clock);
        tok.get();
        now = now.plusSeconds(39);
        tok.get();
        assertEquals(1, tokenCalls);
        now = now.plusSeconds(2);
        tok.get();
        assertEquals(2, tokenCalls);
    }

    @Test
    void azureNotFoundAndAuthFailures() {
        var kv = new AzureKeyVault(addr, null, () -> "t", http);
        status = 404;
        assertNull(kv.fetch("acme", "x"));
        status = 403;
        assertEquals(403, assertThrows(CloudException.class, () -> kv.fetch("acme", "x")).status());
        tokenBody = "{\"error\":\"invalid_client\"}";
        var bad = CloudTokens.azureClientCredentials(addr, "t", "c", () -> "s", http, clock);
        assertThrows(CloudException.class, bad::get);
        assertThrows(CloudException.class, () -> CloudTokens.azureClientCredentials(addr, "t", "c", () -> " ", http, clock).get());
        assertThrows(IllegalArgumentException.class, () -> CloudTokens.azureClientCredentials(addr, "t/../x", "c", () -> "s", http, clock));
    }

    // -------------------------------------------------------------------- GCP

    private static KeyPair rsa() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        return g.generateKeyPair();
    }

    private static String keyFile(KeyPair kp) {
        String pem = "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(kp.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
        return Json.write(Map.of("type", "service_account", "client_email", "wayang@proj.iam.gserviceaccount.com", "private_key", pem));
    }

    @Test
    void gcpExchangesASignedJwtAndReadsTheLatestVersion() throws Exception {
        KeyPair kp = rsa();
        body = "{\"name\":\"x\",\"payload\":{\"data\":\"" + Base64.getEncoder().encodeToString("gcp-secret".getBytes(StandardCharsets.UTF_8)) + "\"}}";
        URI tokenUri = URI.create(addr + "/token");
        var tok = CloudTokens.gcpServiceAccount(() -> keyFile(kp), tokenUri, http, clock);
        var sm = new GcpSecretManager(addr, "my-project-1", "wayang", tok, http);
        assertEquals("gcp-secret", sm.fetch("acme", "api_key"));
        String get = seen.stream().filter(s -> s.startsWith("GET")).findFirst().orElseThrow();
        assertTrue(get.startsWith("GET /v1/projects/my-project-1/secrets/wayang--acme--api_key/versions/latest:access"), get);

        // the assertion really is a JWT signed with the service account's key
        String assertion = java.net.URLDecoder.decode(lastForm.substring(lastForm.indexOf("assertion=") + 10), StandardCharsets.UTF_8);
        assertTrue(lastForm.contains("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer"));
        String[] p = assertion.split("\\.");
        assertEquals(3, p.length);
        Signature v = Signature.getInstance("SHA256withRSA");
        v.initVerify(kp.getPublic());
        v.update((p[0] + "." + p[1]).getBytes(StandardCharsets.US_ASCII));
        assertTrue(v.verify(Base64.getUrlDecoder().decode(p[2])));
        Map<?, ?> claims = (Map<?, ?>) Json.read(new String(Base64.getUrlDecoder().decode(p[1]), StandardCharsets.UTF_8));
        assertEquals("wayang@proj.iam.gserviceaccount.com", claims.get("iss"));
        assertEquals(tokenUri.toString(), claims.get("aud"));
        assertEquals("https://www.googleapis.com/auth/cloud-platform", claims.get("scope"));
        assertEquals(now.getEpochSecond(), ((Number) claims.get("iat")).longValue());
        assertEquals(now.getEpochSecond() + 3600, ((Number) claims.get("exp")).longValue());
    }

    @Test
    void gcpNotFoundBinaryAndBrokenKeyFiles() throws Exception {
        var sm = new GcpSecretManager(addr, "my-project-1", null, () -> "t", http);
        status = 404;
        assertNull(sm.fetch("acme", "x"));
        status = 200;
        body = "{\"payload\":{\"data\":\"" + Base64.getEncoder().encodeToString(new byte[] { (byte) 0xff, (byte) 0xfe }) + "\"}}";
        assertNull(sm.fetch("acme", "x"));
        body = "{\"payload\":{}}";
        assertNull(sm.fetch("acme", "x"));
        var junk = CloudTokens.gcpServiceAccount(() -> "{\"client_email\":\"a\",\"private_key\":\"-----BEGIN PRIVATE KEY-----\\nAAAA\\n-----END PRIVATE KEY-----\"}",
                URI.create(addr + "/token"), http, clock);
        var e = assertThrows(CloudException.class, junk::get);
        assertFalse(e.getMessage().contains("AAAA"));
        assertThrows(CloudException.class, CloudTokens.gcpServiceAccount(() -> "nope", URI.create(addr + "/token"), http, clock)::get);
        assertThrows(IllegalArgumentException.class, () -> new GcpSecretManager(addr, "Bad Project", null, () -> "t", http));
    }

    // ---------------------------------------------------------------- names

    @Test
    void flatIdsCannotCollideAcrossTenants() {
        var allowed = java.util.regex.Pattern.compile("[A-Za-z0-9_-]+");
        assertEquals("b--acme--key", CloudNames.flat("b", "acme", "key", allowed));
        assertEquals("acme--key", CloudNames.flat("", "acme", "key", allowed));
        // tenant "a" + name "b--c" and tenant "a--b" + name "c" would both be a--b--c
        assertThrows(IllegalArgumentException.class, () -> CloudNames.flat("", "a", "b--c", allowed));
        assertThrows(IllegalArgumentException.class, () -> CloudNames.flat("", "a--b", "c", allowed));
        assertThrows(IllegalArgumentException.class, () -> CloudNames.flat("", "a-", "c", allowed));
        assertThrows(IllegalArgumentException.class, () -> CloudNames.flat("", "a", "-c", allowed));
        assertThrows(IllegalArgumentException.class, () -> CloudNames.flat("", "a", "b.c", allowed));
        assertThrows(IllegalArgumentException.class, () -> CloudNames.flat("", "..", "c", allowed));
    }

    // -------------------------------------------------------- the resolver

    @Test
    void theResolverCachesHitsBrieflyAndMissesMoreBrieflyAndFailsClosed() {
        List<String> errors = new ArrayList<>();
        int[] calls = { 0 };
        String[] answer = { "v1" };
        CloudBackend be = (t, n) -> {
            calls[0]++;
            if (n.equals("boom")) {
                throw new CloudException("down", 503);
            }
            if (n.equals("gone")) {
                throw new CloudException("nope", 404);
            }
            if (n.equals("bad")) {
                throw new IllegalArgumentException("x");
            }
            return answer[0];
        };
        var r = new CloudSecretResolver("aws.", be, 30, 5, clock, errors::add);
        assertEquals("v1", r.resolve("acme", "aws.K"));
        answer[0] = "v2";
        assertEquals("v1", r.resolve("acme", "aws.K"), "cached");
        assertEquals(1, calls[0]);
        assertEquals("v2", r.resolve("other", "aws.K"), "the cache is per tenant: this one asks the backend, which now says v2");
        assertEquals(2, calls[0]);
        now = now.plusSeconds(31);
        assertEquals("v2", r.resolve("acme", "aws.K"));
        assertNull(r.resolve("acme", "aws.boom"));
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("down") && !errors.get(0).contains("boom-value"));
        int before = calls[0];
        assertNull(r.resolve("acme", "aws.boom"));
        assertEquals(before, calls[0], "a failure is remembered for the miss TTL");
        now = now.plusSeconds(6);
        r.resolve("acme", "aws.boom");
        assertEquals(before + 1, calls[0]);
        int logged = errors.size();
        assertNull(r.resolve("acme", "aws.gone"));
        assertEquals(logged, errors.size(), "a 404 is just 'no such secret', not a server-log event");
        assertEquals(2L, errors.stream().filter(m -> m.contains("down")).count(), "404 is not an error worth logging; 503 is");
        assertNull(r.resolve("acme", "aws.bad"));
        assertNull(r.resolve("acme", "gcp.K"), "another prefix");
        assertNull(r.resolve(null, "aws.K"));
        answer[0] = "";
        now = now.plusSeconds(100);
        assertNull(r.resolve("acme", "aws.K"), "an empty value is no value");
        answer[0] = "x".repeat(SecretService.MAX_VALUE_CHARS + 1);
        now = now.plusSeconds(10);
        assertNull(r.resolve("acme", "aws.K"));
    }

    @Test
    void routingSendsEachPrefixToItsOwnResolverAndUnconfiguredOnesToNothing() {
        SecretResolver local = (t, n) -> "local:" + n;
        SecretResolver aws = (t, n) -> "aws:" + n;
        SecretResolver res = ExternalSecrets.routing(local, Map.of("aws.", aws));
        assertEquals("aws:aws.K", res.resolve("acme", "aws.K"));
        assertNull(res.resolve("acme", "gcp.K"), "a cloud prefix that is not configured never falls through to the local store");
        assertNull(res.resolve("acme", "azure.K"));
        assertNull(res.resolve("acme", "vault.K"));
        assertNull(res.resolve("acme", "file.K"));
        assertEquals("local:awsome", res.resolve("acme", "awsome"));
        assertEquals("local:aws", res.resolve("acme", "aws"));
        assertNull(ExternalSecrets.routing(null, Map.of()).resolve("acme", "PLAIN"));
    }

    @Test
    void cloudNamesAreExternalAndCannotBeStoredLocally() {
        for (String n : new String[] { "aws.X", "gcp.X", "azure.X", "vault.X", "file.X" }) {
            assertTrue(ExternalSecrets.isExternalName(n), n);
            assertTrue(VaultSecretResolver.isExternalName(n), n);
        }
        assertFalse(ExternalSecrets.isExternalName("aws"));
        assertFalse(ExternalSecrets.isExternalName("awsX"));
        assertFalse(ExternalSecrets.isExternalName(null));
        var svc = new SecretService(new InMemorySecretStore(), new SecretCipher(Map.of("k", new byte[32]), "k"), clock);
        var admin = new Principal("acme", "ann", java.util.Set.of(Role.ADMIN));
        for (String n : new String[] { "aws.X", "gcp.X", "azure.X" }) {
            assertThrows(ApiException.class, () -> svc.put(admin, n, "v"), n);
        }
    }

    // ------------------------------------------------------------- transport

    @Test
    void httpRefusesPlainHttpRedirectsAndHugeResponses() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> CloudHttp.endpoint("http://example.com", false, "x"));
        assertThrows(IllegalArgumentException.class, () -> CloudHttp.endpoint("https://u:p@example.com", false, "x"));
        assertThrows(IllegalArgumentException.class, () -> CloudHttp.endpoint("https://example.com?x=1", false, "x"));
        assertDoesNotThrow(() -> CloudHttp.endpoint("http://127.0.0.1:8200", false, "x"));
        assertDoesNotThrow(() -> CloudHttp.endpoint("http://example.com", true, "x"));
        assertDoesNotThrow(() -> CloudHttp.endpoint("https://example.com/", false, "x"));
        body = "a".repeat(CloudHttp.MAX_RESPONSE_BYTES + 10);
        assertThrows(CloudException.class, () -> http.send("GET", addr, Map.of(), null));
        assertThrows(CloudException.class, () -> http.send("GET", addr, Map.of("x", "a\nb"), null));
        server.removeContext("/");
        server.createContext("/", ex -> {
            ex.getResponseHeaders().add("Location", "http://127.0.0.1:1/");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        assertEquals(302, http.send("GET", addr, Map.of(), null).status(), "a redirect is returned, never followed");
    }
}
