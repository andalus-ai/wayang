package tech.kayys.wayang.api;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

/** Credentials found from the platform (instance role, task role, managed identity, metadata server), against a fake endpoint. */
class CloudDiscoveryTest {

    private record Reply(int status, String body) {
    }

    private HttpServer server;
    private URI addr;
    private final Map<String, Reply> replies = new ConcurrentHashMap<>(); // "METHOD /path" -> reply
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private final List<Map<String, String>> headers = new CopyOnWriteArrayList<>();
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
    private final CloudHttp http = new CloudHttp(Duration.ofSeconds(5));

    @BeforeEach
    void up() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            ex.getRequestBody().readAllBytes();
            String k = ex.getRequestMethod() + " " + ex.getRequestURI().getRawPath() + (ex.getRequestURI().getRawQuery() == null ? "" : "?" + ex.getRequestURI().getRawQuery());
            calls.add(k);
            Map<String, String> h = new java.util.HashMap<>();
            ex.getRequestHeaders().forEach((n, v) -> h.put(n.toLowerCase(), v.get(0)));
            headers.add(h);
            Reply r = replies.getOrDefault(k, new Reply(404, "{}"));
            byte[] b = r.body().getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(r.status(), b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
        addr = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void down() {
        server.stop(0);
    }

    private static String creds(String exp) {
        return "{\"AccessKeyId\":\"AK\",\"SecretAccessKey\":\"SK\",\"Token\":\"TOK\",\"Expiration\":\"" + exp + "\"}";
    }

    @Test
    void imdsV2FetchesASessionTokenThenTheRoleThenTheCredentialsAndCachesThem() {
        replies.put("PUT /latest/api/token", new Reply(200, "sess-token"));
        replies.put("GET /latest/meta-data/iam/security-credentials/", new Reply(200, "my-role\n"));
        replies.put("GET /latest/meta-data/iam/security-credentials/my-role", new Reply(200, creds("2026-01-01T01:00:00Z")));
        var s = AwsCredentialSources.imds(addr, http, clock);
        var c = s.get();
        assertEquals("AK", c.accessKeyId());
        assertEquals("SK", c.secretAccessKey());
        assertEquals("TOK", c.sessionToken());
        assertEquals("300", headers.get(0).get("x-aws-ec2-metadata-token-ttl-seconds"));
        assertEquals("sess-token", headers.get(1).get("x-aws-ec2-metadata-token"));
        assertEquals("sess-token", headers.get(2).get("x-aws-ec2-metadata-token"));
        s.get();
        assertEquals(3, calls.size(), "cached");
        now = now.plusSeconds(3600 - 301);
        s.get();
        assertEquals(3, calls.size(), "still cached until five minutes before expiry");
        now = now.plusSeconds(2);
        s.get();
        assertEquals(6, calls.size(), "refreshed within five minutes of expiry");
    }

    @Test
    void imdsRefusesWhenThereIsNoTokenNoRoleOrAnOddRoleName() {
        assertThrows(CloudException.class, AwsCredentialSources.imds(addr, http, clock)::get);
        replies.put("PUT /latest/api/token", new Reply(200, "t"));
        assertThrows(CloudException.class, AwsCredentialSources.imds(addr, http, clock)::get);
        replies.put("GET /latest/meta-data/iam/security-credentials/", new Reply(200, "../../x"));
        assertThrows(CloudException.class, AwsCredentialSources.imds(addr, http, clock)::get);
        assertFalse(calls.stream().anyMatch(c -> c.contains("..")));
    }

    @Test
    void ecsTaskRoleReadsTheCredentialsUriWithItsAuthorizationToken() {
        replies.put("GET /v2/credentials/abc", new Reply(200, creds("2026-01-01T06:00:00Z")));
        var c = AwsCredentialSources.container(URI.create(addr + "/v2/credentials/abc"), () -> " task-token\n", http, clock).get();
        assertEquals("AK", c.accessKeyId());
        assertEquals("task-token", headers.get(0).get("authorization"));
        replies.put("GET /v2/credentials/abc", new Reply(200, "{\"AccessKeyId\":\"AK\"}"));
        assertThrows(CloudException.class, AwsCredentialSources.container(URI.create(addr + "/v2/credentials/abc"), null, http, clock)::get);
        replies.put("GET /v2/credentials/abc", new Reply(500, "boom SK"));
        var e = assertThrows(CloudException.class, AwsCredentialSources.container(URI.create(addr + "/v2/credentials/abc"), null, http, clock)::get);
        assertFalse(e.getMessage().contains("SK"));
    }

    @Test
    void gcpMetadataServerGivesAnAccessTokenForTheAttachedAccount() {
        replies.put("GET /computeMetadata/v1/instance/service-accounts/default/token", new Reply(200, "{\"access_token\":\"gtok\",\"expires_in\":3599,\"token_type\":\"Bearer\"}"));
        var t = CloudTokens.gcpMetadata(addr, http, clock);
        assertEquals("gtok", t.get());
        assertEquals("Google", headers.get(0).get("metadata-flavor"));
        t.get();
        assertEquals(1, calls.size());
        replies.put("GET /computeMetadata/v1/instance/service-accounts/default/token", new Reply(200, "{}"));
        now = now.plusSeconds(4000);
        assertThrows(CloudException.class, t::get);
    }

    @Test
    void azureManagedIdentityViaImdsAndViaAppService() {
        String imds = "GET /metadata/identity/oauth2/token?api-version=2018-02-01&resource=https%3A%2F%2Fvault.azure.net&client_id=cid-1";
        replies.put(imds, new Reply(200, "{\"access_token\":\"atok\",\"expires_in\":\"86399\"}"));
        var t = CloudTokens.azureManagedIdentity(URI.create(addr + "/metadata/identity/oauth2/token"), null, "cid-1", http, clock);
        assertEquals("atok", t.get());
        assertEquals("true", headers.get(0).get("metadata"));
        assertNull(headers.get(0).get("x-identity-header"));
        now = now.plusSeconds(3000);
        t.get();
        assertEquals(1, calls.size(), "a string expires_in is understood (capped at an hour), so it is still good 50 minutes in");
        now = now.plusSeconds(600);
        t.get();
        assertEquals(2, calls.size(), "and refreshed a minute before the hour is up");

        String app = "GET /msi/token?api-version=2019-08-01&resource=https%3A%2F%2Fvault.azure.net";
        replies.put(app, new Reply(200, "{\"access_token\":\"btok\",\"expires_on\":\"x\",\"expires_in\":100}"));
        var t2 = CloudTokens.azureManagedIdentity(URI.create(addr + "/msi/token"), "secret-hdr", null, http, clock);
        assertEquals("btok", t2.get());
        assertEquals("secret-hdr", headers.get(headers.size() - 1).get("x-identity-header"));
        assertNull(headers.get(headers.size() - 1).get("metadata"));
        assertThrows(IllegalArgumentException.class, () -> CloudTokens.azureManagedIdentity(addr, null, "a&b", http, clock));
    }
}
