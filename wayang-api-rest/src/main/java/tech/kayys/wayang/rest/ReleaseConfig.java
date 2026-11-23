package tech.kayys.wayang.rest;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import tech.kayys.wayang.api.project.ReleaseService;
import tech.kayys.wayang.api.project.ReleaseSigner;
import tech.kayys.wayang.api.WayangApi;

/** Release generation (the second phase: from a designed project to a standalone or service project). {@code wayang.release.wayang-version} is the Wayang version the generated pom depends on. */
@ApplicationScoped
public class ReleaseConfig {

    @ConfigProperty(name = "wayang.release.wayang-version", defaultValue = "0.0.1")
    String wayangVersion;

    /** Ed25519 release signing: PKCS#8 private key and X.509 public key files (PEM or base64). Files only, never inline in configuration. */
    @ConfigProperty(name = "wayang.release.signing-private-key-file")
    Optional<String> privateKeyFile;
    @ConfigProperty(name = "wayang.release.signing-public-key-file")
    Optional<String> publicKeyFile;

    @Inject
    WayangApi api;
    @Inject
    WorkspaceConfig workspace;

    private volatile ReleaseService service;

    public synchronized ReleaseService service() {
        if (service == null) {
            service = new ReleaseService(workspace.service(), api, wayangVersion);
            if (privateKeyFile.isPresent() != publicKeyFile.isPresent()) {
                throw new IllegalStateException("Set both wayang.release.signing-private-key-file and wayang.release.signing-public-key-file, or neither");
            }
            if (privateKeyFile.isPresent()) {
                try {
                    service.withSigner(new ReleaseSigner(Files.readString(Path.of(privateKeyFile.get())), Files.readString(Path.of(publicKeyFile.get()))));
                } catch (java.io.IOException e) {
                    throw new IllegalStateException("Cannot read the release signing key files");
                }
            }
        }
        return service;
    }
}
