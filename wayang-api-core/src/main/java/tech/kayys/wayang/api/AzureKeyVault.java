package tech.kayys.wayang.api;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import tech.kayys.wayang.runtime.json.Json;

/**
 * Azure Key Vault, read-only: {@code ${secret:azure.NAME}} reads the secret {@code [base--]tenant--NAME} (Key Vault names are one
 * flat string of letters, digits and dashes, so names with {@code _} or {@code .} cannot be used, and no part may contain
 * {@code --}). Access goes through an Azure AD app (client credentials); a managed identity is not implemented.
 */
public final class AzureKeyVault implements CloudBackend {

    private static final Pattern ALLOWED = Pattern.compile("[A-Za-z0-9-]+");
    private static final Pattern BASE = Pattern.compile("[A-Za-z0-9-]*");

    private final URI vault;
    private final String base;
    private final Supplier<String> token;
    private final CloudHttp http;

    public AzureKeyVault(URI vaultUrl, String base, Supplier<String> token, CloudHttp http) {
        if (base != null && !BASE.matcher(base).matches()) {
            throw new IllegalArgumentException("wayang.cloud.azure.base-path may only have letters, digits and dashes");
        }
        this.vault = vaultUrl;
        this.base = base == null ? "" : base;
        this.token = token;
        this.http = http;
    }

    @Override
    public String fetch(String tenant, String name) {
        String id = CloudNames.flat(base, tenant, name, ALLOWED);
        String t = token.get();
        if (t == null || t.isBlank()) {
            throw new CloudException("No Azure access token", 0);
        }
        CloudHttp.Response r = http.send("GET", URI.create(vault + "/secrets/" + id + "?api-version=7.4"),
                Map.of("authorization", "Bearer " + t.strip(), "accept", "application/json"), null);
        if (r.status() == 404) {
            return null;
        }
        if (r.status() != 200) {
            throw new CloudException("Azure Key Vault answered " + r.status(), r.status());
        }
        try {
            if (Json.read(new String(r.body(), StandardCharsets.UTF_8)) instanceof Map<?, ?> m && m.get("value") instanceof String s) {
                return s;
            }
        } catch (RuntimeException e) {
            throw new CloudException("Azure Key Vault answered with invalid JSON", 0);
        }
        return null;
    }
}
