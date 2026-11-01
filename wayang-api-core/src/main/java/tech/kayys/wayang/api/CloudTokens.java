package tech.kayys.wayang.api;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import tech.kayys.wayang.runtime.json.Json;

/** OAuth access-token suppliers for the cloud secret services: cached until shortly before they expire, never logged. */
public final class CloudTokens {

    private CloudTokens() {
    }

    private static final class Cache implements Supplier<String> {
        private final Supplier<Map.Entry<String, Long>> fetch; // token, seconds valid
        private final Clock clock;
        private String token;
        private Instant until = Instant.EPOCH;

        Cache(Supplier<Map.Entry<String, Long>> fetch, Clock clock) {
            this.fetch = fetch;
            this.clock = clock;
        }

        @Override
        public synchronized String get() {
            Instant now = clock.instant();
            if (token == null || !now.isBefore(until)) {
                Map.Entry<String, Long> e = fetch.get();
                long life = Math.max(0, Math.min(3600, e.getValue()));
                token = e.getKey();
                until = now.plusSeconds(Math.max(0, life - 60));
            }
            return token;
        }
    }

    /** Azure AD client-credentials grant for Key Vault. The client secret is re-read from its supplier on every refresh. */
    public static Supplier<String> azureClientCredentials(URI authority, String tenantId, String clientId, Supplier<String> clientSecret,
            CloudHttp http, Clock clock) {
        if (tenantId == null || !tenantId.matches("[A-Za-z0-9.-]{1,100}") || clientId == null || !clientId.matches("[A-Za-z0-9-]{1,100}")) {
            throw new IllegalArgumentException("Azure tenant-id or client-id is malformed");
        }
        URI url = URI.create(authority + "/" + tenantId + "/oauth2/v2.0/token");
        return new Cache(() -> {
            String secret = clientSecret.get();
            if (secret == null || secret.isBlank()) {
                throw new CloudException("No Azure client secret", 0);
            }
            String form = "grant_type=client_credentials&client_id=" + enc(clientId) + "&client_secret=" + enc(secret.strip())
                    + "&scope=" + enc("https://vault.azure.net/.default");
            return exchange(http, url, Map.of("content-type", "application/x-www-form-urlencoded", "accept", "application/json"),
                    form.getBytes(StandardCharsets.UTF_8), "Azure AD token request");
        }, clock);
    }

    /** Google service-account flow: a signed JWT (RS256) exchanged for an access token with the cloud-platform scope. */
    public static Supplier<String> gcpServiceAccount(Supplier<String> keyJson, URI tokenUri, CloudHttp http, Clock clock) {
        return new Cache(() -> {
            String raw = keyJson.get();
            String email;
            PrivateKey key;
            try {
                if (!(Json.read(raw) instanceof Map<?, ?> m) || !(m.get("client_email") instanceof String e) || !(m.get("private_key") instanceof String pem)) {
                    throw new CloudException("The Google service-account key file is not in the expected format", 0);
                }
                email = e;
                key = privateKey(pem);
            } catch (CloudException e) {
                throw e;
            } catch (RuntimeException | java.security.GeneralSecurityException e) {
                throw new CloudException("The Google service-account key could not be read", 0);
            }
            long iat = clock.instant().getEpochSecond();
            Map<String, Object> claims = new LinkedHashMap<>();
            claims.put("iss", email);
            claims.put("scope", "https://www.googleapis.com/auth/cloud-platform");
            claims.put("aud", tokenUri.toString());
            claims.put("iat", iat);
            claims.put("exp", iat + 3600);
            String jwt;
            try {
                String head = b64(Json.write(Map.of("alg", "RS256", "typ", "JWT")).getBytes(StandardCharsets.UTF_8));
                String signing = head + "." + b64(Json.write(claims).getBytes(StandardCharsets.UTF_8));
                Signature s = Signature.getInstance("SHA256withRSA");
                s.initSign(key);
                s.update(signing.getBytes(StandardCharsets.US_ASCII));
                jwt = signing + "." + b64(s.sign());
            } catch (java.security.GeneralSecurityException e) {
                throw new CloudException("The Google service-account key could not sign", 0);
            }
            String form = "grant_type=" + enc("urn:ietf:params:oauth:grant-type:jwt-bearer") + "&assertion=" + enc(jwt);
            return exchange(http, tokenUri, Map.of("content-type", "application/x-www-form-urlencoded", "accept", "application/json"),
                    form.getBytes(StandardCharsets.UTF_8), "Google OAuth token request");
        }, clock);
    }

    /**
     * Google metadata server (GCE, GKE, Cloud Run): the attached service account's token. The metadata address is plain http on a
     * link-local network, so it is operator-chosen and fixed, never taken from a run.
     */
    public static Supplier<String> gcpMetadata(URI base, CloudHttp http, Clock clock) {
        URI url = URI.create(base + "/computeMetadata/v1/instance/service-accounts/default/token");
        return new Cache(() -> exchange(http, url, "GET", Map.of("metadata-flavor", "Google", "accept", "application/json"), null, "The Google metadata server"), clock);
    }

    /**
     * Azure managed identity. {@code identityHeader} null = the VM/AKS IMDS endpoint (header Metadata: true); set = the App
     * Service / Functions flavour (IDENTITY_ENDPOINT and IDENTITY_HEADER, header X-IDENTITY-HEADER). {@code clientId} picks a
     * user-assigned identity.
     */
    public static Supplier<String> azureManagedIdentity(URI endpoint, String identityHeader, String clientId, CloudHttp http, Clock clock) {
        if (clientId != null && !clientId.isEmpty() && !clientId.matches("[A-Za-z0-9-]{1,100}")) {
            throw new IllegalArgumentException("Azure client-id is malformed");
        }
        boolean appService = identityHeader != null && !identityHeader.isEmpty();
        String q = (endpoint.getRawQuery() == null ? "?" : "&") + "api-version=" + (appService ? "2019-08-01" : "2018-02-01") + "&resource="
                + enc("https://vault.azure.net") + (clientId == null || clientId.isEmpty() ? "" : "&client_id=" + enc(clientId));
        URI url = URI.create(endpoint + q);
        Map<String, String> h = appService ? Map.of("x-identity-header", identityHeader, "accept", "application/json")
                : Map.of("metadata", "true", "accept", "application/json");
        return new Cache(() -> exchange(http, url, "GET", h, null, "Azure managed identity"), clock);
    }

    /** Reads a whole small file each time it is asked, so a rotated secret file applies. */
    public static Supplier<String> fileText(Path p) {
        return () -> {
            try {
                if (Files.size(p) > 64 * 1024) {
                    throw new CloudException("A credential file is too large", 0);
                }
                return Files.readString(p, StandardCharsets.UTF_8);
            } catch (java.io.IOException e) {
                throw new CloudException("A credential file could not be read", 0);
            }
        };
    }

    private static Map.Entry<String, Long> exchange(CloudHttp http, URI url, Map<String, String> headers, byte[] body, String who) {
        return exchange(http, url, "POST", headers, body, who);
    }

    private static Map.Entry<String, Long> exchange(CloudHttp http, URI url, String method, Map<String, String> headers, byte[] body, String who) {
        CloudHttp.Response r = http.send(method, url, headers, body);
        if (r.status() / 100 != 2) {
            throw new CloudException(who + " answered " + r.status(), r.status());
        }
        try {
            if (Json.read(new String(r.body(), StandardCharsets.UTF_8)) instanceof Map<?, ?> m && m.get("access_token") instanceof String t && !t.isBlank()) {
                long life = lifetime(m.get("expires_in"));
                return Map.entry(t, life);
            }
        } catch (RuntimeException e) {
            // falls through
        }
        throw new CloudException(who + " answered without a usable token", 0);
    }

    /** expires_in is a number from most services and a numeric string from Azure's IMDS. */
    private static long lifetime(Object v) {
        if (v instanceof Number n) {
            return n.longValue();
        }
        if (v instanceof String t && t.matches("[0-9]{1,9}")) {
            return Long.parseLong(t);
        }
        return 300;
    }

    private static PrivateKey privateKey(String pem) throws java.security.GeneralSecurityException {
        String body = pem.replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "").replaceAll("\\s", "");
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(body)));
    }

    private static String b64(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
