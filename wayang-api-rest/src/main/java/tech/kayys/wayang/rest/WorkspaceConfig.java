package tech.kayys.wayang.rest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Base64;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import tech.kayys.wayang.api.trigger.TriggerService;
import tech.kayys.wayang.api.WayangApi;
import tech.kayys.wayang.api.project.WorkspaceService;

/**
 * Workspace export/import. Optional signing: with {@code wayang.workspace.signing-key-file} (a file holding at least 32 characters,
 * or base64 of 32+ bytes) exported bundles carry an HMAC and this server only imports bundles signed with the same key, which
 * is what makes a bundle from "our other server" distinguishable from one anybody made.
 */
@ApplicationScoped
public class WorkspaceConfig {

    @ConfigProperty(name = "wayang.workspace.signing-key-file")
    Optional<String> signingKeyFile;
    @ConfigProperty(name = "wayang.workspace.signing-key")
    Optional<String> signingKey;

    @Inject
    WayangApi api;
    @Inject
    TriggerService triggers;
    @Inject
    ConfigsConfig config;
    @Inject
    SecretsConfig secrets;
    @Inject
    Clock clock;

    private volatile WorkspaceService service;

    public synchronized WorkspaceService service() {
        if (service == null) {
            byte[] key = null;
            try {
                String raw = signingKeyFile.isPresent() ? Files.readString(Path.of(signingKeyFile.get())).strip() : signingKey.orElse(null);
                if (raw != null) {
                    key = raw.length() >= 32 && !raw.matches("[A-Za-z0-9+/=]{43,}") ? raw.getBytes(StandardCharsets.UTF_8) : Base64.getDecoder().decode(raw);
                    if (key.length < 32) {
                        throw new IllegalStateException("The workspace signing key must be at least 32 bytes");
                    }
                }
            } catch (java.io.IOException e) {
                throw new IllegalStateException("Cannot read wayang.workspace.signing-key-file");
            }
            service = new WorkspaceService(api, triggers, config.service(), secrets.service().orElse(null), key, clock);
        }
        return service;
    }
}
