package tech.kayys.wayang.rest;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import tech.kayys.wayang.api.project.ReleaseService;
import tech.kayys.wayang.api.WayangApi;

/** Release generation (the second phase: from a designed project to a standalone or service project). {@code wayang.release.wayang-version} is the Wayang version the generated pom depends on. */
@ApplicationScoped
public class ReleaseConfig {

    @ConfigProperty(name = "wayang.release.wayang-version", defaultValue = "0.0.1")
    String wayangVersion;

    @Inject
    WayangApi api;
    @Inject
    WorkspaceConfig workspace;

    private volatile ReleaseService service;

    public synchronized ReleaseService service() {
        if (service == null) {
            service = new ReleaseService(workspace.service(), api, wayangVersion);
        }
        return service;
    }
}
