package tech.kayys.wayang.api.oauth;

import tech.kayys.wayang.api.ApiException;
import tech.kayys.wayang.api.Permission;
import tech.kayys.wayang.api.Principal;
import tech.kayys.wayang.api.secret.SecretService;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import tech.kayys.wayang.runtime.OAuthTokenEndpoint;
import tech.kayys.wayang.runtime.SecretResolver;

/**
 * OAuth2 authorization-code + PKCE (S256) for connected accounts, and the runtime resolver for them.
 *
 * <p>Flow: {@link #create} registers a connection (provider, client id, name of the secret holding the client
 * secret, scopes within the provider's allow-list); {@link #authorize} returns the consent URL (single-use,
 * 10-minute state bound to tenant and connection; redirect URI is fixed by deployment config, never by the caller);
 * {@link #callback} (unauthenticated browser redirect, authenticated by the state alone) exchanges the code and stores
 * the refresh token under the reserved secret {@code oauth-refresh.<id>}.
 *
 * <p>Runtime: wraps the normal {@link SecretResolver}. {@code ${secret:oauth.<id>}} yields a fresh access token
 * (cached until 60 s before expiry; rotating refresh tokens are persisted). Every other reserved name resolves to
 * null so a workflow can never read a refresh token. Failures resolve to null (fail closed); {@code invalid_grant}
 * marks the connection REVOKED.
 */
public final class OAuthService implements SecretResolver {

    /** Provider endpoints and the scopes a connection may request. */
    public record Provider(String name, String authUrl, String tokenUrl, List<String> allowedScopes, Map<String, String> authParams) {
        public Provider {
            allowedScopes = List.copyOf(allowedScopes);
            authParams = Map.copyOf(authParams);
        }

        /** Gmail, Calendar events and Sheets; deliberately no full-mailbox scope and no Drive-wide scope. */
        public static Provider google() {
            return new Provider("google", "https://accounts.google.com/o/oauth2/v2/auth", "https://oauth2.googleapis.com/token",
                    List.of("https://www.googleapis.com/auth/gmail.readonly", "https://www.googleapis.com/auth/gmail.send",
                            "https://www.googleapis.com/auth/gmail.metadata", "https://www.googleapis.com/auth/gmail.compose",
                            "https://www.googleapis.com/auth/calendar.events", "https://www.googleapis.com/auth/calendar.readonly",
                            "https://www.googleapis.com/auth/spreadsheets"),
                    Map.of("access_type", "offline", "prompt", "consent"));
        }
    }

    public static final String ACCESS_PREFIX = "oauth.";
    static final String REFRESH_PREFIX = "oauth-refresh.";
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,60}");
    private static final Duration STATE_TTL = Duration.ofMinutes(10);
    private static final Duration SKEW = Duration.ofSeconds(60);

    private record Cached(String token, Instant expires) {
    }

    private final OAuthStore store;
    private final SecretService secrets;
    private final SecretResolver plain;
    private final OAuthTokenEndpoint endpoint;
    private final Map<String, Provider> providers = new LinkedHashMap<>();
    private final String redirectUri;
    private final Clock clock;
    private final SecureRandom rnd = new SecureRandom();
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    public OAuthService(OAuthStore store, SecretService secrets, OAuthTokenEndpoint endpoint, List<Provider> providers,
            String redirectUri, Clock clock) {
        this.store = store;
        this.secrets = secrets;
        this.plain = secrets;
        this.endpoint = endpoint;
        providers.forEach(p -> this.providers.put(p.name(), p));
        this.redirectUri = redirectUri;
        this.clock = clock;
    }

    public OAuthConnection create(Principal p, String id, String provider, String clientId, String clientSecretName,
            List<String> scopes) {
        need(p, Permission.SECRET_MANAGE);
        if (id == null || !ID.matcher(id).matches()) {
            throw ApiException.badRequest("Connection id must match " + ID.pattern());
        }
        Provider pr = providers.get(provider);
        if (pr == null) {
            throw ApiException.badRequest("Unknown provider '" + provider + "'");
        }
        if (clientId == null || clientId.isBlank() || clientId.length() > 300) {
            throw ApiException.badRequest("clientId is required");
        }
        if (clientSecretName == null || !SecretService.NAME.matcher(clientSecretName).matches() || SecretService.reserved(clientSecretName)) {
            throw ApiException.badRequest("clientSecretName must name a stored secret");
        }
        if (scopes == null || scopes.isEmpty()) {
            throw ApiException.badRequest("At least one scope is required");
        }
        for (String s : scopes) {
            if (!pr.allowedScopes().contains(s)) {
                throw ApiException.badRequest("Scope not allowed for " + provider + ": " + s);
            }
        }
        OAuthConnection c = new OAuthConnection(p.tenantId(), id, provider, clientId, clientSecretName, scopes,
                OAuthConnection.Status.PENDING, p.userId(), clock.instant(), null);
        store.create(c);
        return c;
    }

    public List<OAuthConnection> list(Principal p) {
        need(p, Permission.SECRET_LIST);
        return store.list(p.tenantId());
    }

    public void delete(Principal p, String id) {
        need(p, Permission.SECRET_MANAGE);
        if (!store.delete(p.tenantId(), id)) {
            throw ApiException.notFound("connection " + id);
        }
        secrets.deleteSystem(p.tenantId(), REFRESH_PREFIX + id);
        cache.remove(p.tenantId() + "\u0000" + id);
    }

    /** @return the provider consent URL the person must open */
    public String authorize(Principal p, String id) {
        need(p, Permission.SECRET_MANAGE);
        OAuthConnection c = store.get(p.tenantId(), id).orElseThrow(() -> ApiException.notFound("connection " + id));
        Provider pr = providers.get(c.provider());
        String state = token(32);
        String verifier = token(64);
        store.saveState(new OAuthStore.State(state, c.tenant(), c.id(), verifier, clock.instant().plus(STATE_TTL)));
        Map<String, String> q = new LinkedHashMap<>();
        q.put("response_type", "code");
        q.put("client_id", c.clientId());
        q.put("redirect_uri", redirectUri);
        q.put("scope", String.join(" ", c.scopes()));
        q.put("state", state);
        q.put("code_challenge", challenge(verifier));
        q.put("code_challenge_method", "S256");
        q.putAll(pr.authParams());
        StringBuilder sb = new StringBuilder(pr.authUrl()).append(pr.authUrl().contains("?") ? '&' : '?');
        boolean first = true;
        for (var e : q.entrySet()) {
            if (!first) {
                sb.append('&');
            }
            first = false;
            sb.append(enc(e.getKey())).append('=').append(enc(e.getValue()));
        }
        return sb.toString();
    }

    /** Completes the flow. Unauthenticated: the single-use state is the credential. @return the connected connection */
    public OAuthConnection callback(String state, String code, String error) {
        if (state == null || state.isBlank()) {
            throw ApiException.badRequest("Missing state");
        }
        OAuthStore.State st = store.takeState(state, clock.instant())
                .orElseThrow(() -> ApiException.badRequest("Unknown, used or expired state"));
        if (error != null && !error.isBlank()) {
            throw ApiException.badRequest("Authorisation was not granted");
        }
        if (code == null || code.isBlank() || code.length() > 2000) {
            throw ApiException.badRequest("Missing code");
        }
        OAuthConnection c = store.get(st.tenant(), st.connectionId()).orElseThrow(() -> ApiException.notFound("connection"));
        Provider pr = providers.get(c.provider());
        String secret = plain.resolve(c.tenant(), c.clientSecretName());
        if (secret == null) {
            throw ApiException.badRequest("Client secret '" + c.clientSecretName() + "' is not stored");
        }
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", code);
        form.put("redirect_uri", redirectUri);
        form.put("client_id", c.clientId());
        form.put("client_secret", secret);
        form.put("code_verifier", st.verifier());
        Map<String, Object> res;
        try {
            res = endpoint.post(pr.tokenUrl(), form);
        } catch (OAuthTokenEndpoint.OAuthError e) {
            throw ApiException.badRequest("Provider refused the code: " + e.error());
        } catch (Exception e) {
            throw new ApiException(ApiException.Code.UNAVAILABLE, "Token endpoint unreachable");
        }
        if (!(res.get("refresh_token") instanceof String rt) || rt.isBlank()) {
            throw ApiException.badRequest("Provider returned no refresh token (grant offline access and consent again)");
        }
        secrets.putSystem(c.tenant(), REFRESH_PREFIX + c.id(), rt, "oauth");
        OAuthConnection done = c.with(OAuthConnection.Status.CONNECTED, clock.instant());
        store.update(done);
        remember(done, res);
        return done;
    }

    @Override
    public String resolve(String tenantId, String name) {
        if (tenantId == null || name == null) {
            return null;
        }
        if (name.startsWith(ACCESS_PREFIX)) {
            return accessToken(tenantId, name.substring(ACCESS_PREFIX.length()));
        }
        if (SecretService.reserved(name)) {
            return null; // refresh tokens are never readable by workflows
        }
        return plain.resolve(tenantId, name);
    }

    private String accessToken(String tenant, String id) {
        String key = tenant + "\u0000" + id;
        Cached c = cache.get(key);
        if (c != null && c.expires().isAfter(clock.instant().plus(SKEW))) {
            return c.token();
        }
        synchronized (locks.computeIfAbsent(key, k -> new Object())) {
            c = cache.get(key);
            if (c != null && c.expires().isAfter(clock.instant().plus(SKEW))) {
                return c.token();
            }
            OAuthConnection conn = store.get(tenant, id).orElse(null);
            if (conn == null || conn.status() != OAuthConnection.Status.CONNECTED) {
                return null;
            }
            String refresh = plain.resolve(tenant, REFRESH_PREFIX + id);
            String secret = plain.resolve(tenant, conn.clientSecretName());
            if (refresh == null || secret == null) {
                return null;
            }
            Map<String, String> form = new LinkedHashMap<>();
            form.put("grant_type", "refresh_token");
            form.put("refresh_token", refresh);
            form.put("client_id", conn.clientId());
            form.put("client_secret", secret);
            try {
                Map<String, Object> res = endpoint.post(providers.get(conn.provider()).tokenUrl(), form);
                if (res.get("refresh_token") instanceof String rt && !rt.isBlank() && !rt.equals(refresh)) {
                    secrets.putSystem(tenant, REFRESH_PREFIX + id, rt, "oauth");
                }
                return remember(conn, res);
            } catch (OAuthTokenEndpoint.OAuthError e) {
                if (e.error().equals("invalid_grant")) {
                    store.update(conn.with(OAuthConnection.Status.REVOKED, conn.connectedAt()));
                    cache.remove(key);
                }
                return null;
            } catch (Exception e) {
                return null;
            }
        }
    }

    private String remember(OAuthConnection c, Map<String, Object> res) {
        if (!(res.get("access_token") instanceof String at) || at.isBlank()) {
            return null;
        }
        long ttl = res.get("expires_in") instanceof Number n ? Math.max(0, n.longValue()) : 300;
        cache.put(c.tenant() + "\u0000" + c.id(), new Cached(at, clock.instant().plusSeconds(ttl)));
        return at;
    }

    private String token(int bytes) {
        byte[] b = new byte[bytes];
        rnd.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static String challenge(String verifier) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(d);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static void need(Principal p, Permission perm) {
        if (p == null) {
            throw new ApiException(ApiException.Code.UNAUTHORIZED, "Not authenticated");
        }
        if (!p.can(perm)) {
            throw ApiException.forbidden(perm);
        }
    }
}
