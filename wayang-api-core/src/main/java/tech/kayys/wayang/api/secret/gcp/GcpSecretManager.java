package tech.kayys.wayang.api.secret.cloud;

import tech.kayys.wayang.api.CloudBackend;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import tech.kayys.wayang.runtime.json.Json;

/**
 * Google Cloud Secret Manager, read-only: {@code ${secret:gcp.NAME}} reads the latest version of the secret
 * {@code [base--]tenant--NAME} in one project (secret ids are one flat string of letters, digits, {@code _} and {@code -}; names
 * with {@code .} cannot be used, and no part may contain {@code --}). Access is a service-account key; the metadata server is not
 * implemented.
 */
public final class GcpSecretManager implements CloudBackend {

    private static final Pattern ALLOWED = Pattern.compile("[A-Za-z0-9_-]+");
    private static final Pattern PROJECT = Pattern.compile("[a-z][a-z0-9-]{4,28}[a-z0-9]|[0-9]{1,20}");

    private final URI endpoint;
    private final String project;
    private final String base;
    private final Supplier<String> token;
    private final CloudHttp http;

    public GcpSecretManager(URI endpoint, String project, String base, Supplier<String> token, CloudHttp http) {
        if (project == null || !PROJECT.matcher(project).matches()) {
            throw new IllegalArgumentException("Not a Google Cloud project id");
        }
        if (base != null && !base.isEmpty() && !ALLOWED.matcher(base).matches()) {
            throw new IllegalArgumentException("wayang.cloud.gcp.base-path may only have letters, digits, _ and -");
        }
        this.endpoint = endpoint != null ? endpoint : URI.create("https://secretmanager.googleapis.com");
        this.project = project;
        this.base = base == null ? "" : base;
        this.token = token;
        this.http = http;
    }

    @Override
    public String fetch(String tenant, String name) {
        String id = CloudNames.flat(base, tenant, name, ALLOWED);
        String t = token.get();
        if (t == null || t.isBlank()) {
            throw new CloudException("No Google access token", 0);
        }
        CloudHttp.Response r = http.send("GET", URI.create(endpoint + "/v1/projects/" + project + "/secrets/" + id + "/versions/latest:access"),
                Map.of("authorization", "Bearer " + t.strip(), "accept", "application/json"), null);
        if (r.status() == 404) {
            return null;
        }
        if (r.status() != 200) {
            throw new CloudException("Google Secret Manager answered " + r.status(), r.status());
        }
        try {
            if (Json.read(new String(r.body(), StandardCharsets.UTF_8)) instanceof Map<?, ?> m && m.get("payload") instanceof Map<?, ?> p
                    && p.get("data") instanceof String b64) {
                byte[] raw = Base64.getDecoder().decode(b64);
                return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString();
            }
        } catch (CharacterCodingException e) {
            return null; // binary payload
        } catch (RuntimeException e) {
            throw new CloudException("Google Secret Manager answered with an unreadable payload", 0);
        }
        return null;
    }
}
