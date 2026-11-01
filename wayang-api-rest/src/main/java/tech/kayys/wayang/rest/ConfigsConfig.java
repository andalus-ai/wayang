package tech.kayys.wayang.rest;

import java.time.Clock;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import tech.kayys.wayang.api.ConfigService;
import tech.kayys.wayang.api.ConfigStore;
import tech.kayys.wayang.api.DefinitionKind;
import tech.kayys.wayang.api.DefinitionStore;
import tech.kayys.wayang.api.ProjectStore;
import tech.kayys.wayang.api.Studio;
import tech.kayys.wayang.store.jdbc.JdbcConfigStore;

/**
 * Plain settings for {@code ${config:NAME}} and the workspace bundle; stored with {@code wayang.store}. A process's own project
 * is found from the definition it is run from, so a project can override a tenant-wide setting for its processes only.
 */
@ApplicationScoped
public class ConfigsConfig {

    @ConfigProperty(name = "wayang.store", defaultValue = "memory")
    String storeKind;

    @Inject
    WayangBeans beans;
    @Inject
    ProjectStore projects;
    @Inject
    DefinitionStore definitions;
    @Inject
    Clock clock;

    private volatile ConfigService service;

    public synchronized ConfigService service() {
        if (service == null) {
            ConfigStore store = "jdbc".equals(storeKind) ? new JdbcConfigStore(beans.jdbc()) : new ConfigStore.InMemory();
            service = new ConfigService(store, x -> projects.get(x[0], x[1]).isPresent(), (tenant, process) -> {
                var d = definitions.getLatest(tenant, DefinitionKind.PROCESS, process).or(() -> definitions.getDraft(tenant, DefinitionKind.PROCESS, process));
                return d.map(x -> Studio.projectId(x.document())).orElse(null);
            }, clock);
        }
        return service;
    }
}
