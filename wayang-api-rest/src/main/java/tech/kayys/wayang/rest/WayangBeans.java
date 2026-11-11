package tech.kayys.wayang.rest;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import javax.sql.DataSource;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import tech.kayys.wayang.adapter.gamelan.GamelanClients;
import tech.kayys.wayang.adapter.gamelan.GamelanRunEngine;
import tech.kayys.wayang.adapter.gamelan.WayangRuntime;
import tech.kayys.wayang.api.definition.DefinitionStore;
import tech.kayys.wayang.api.run.EventBus;
import tech.kayys.wayang.api.project.ProjectStore;
import tech.kayys.wayang.api.run.ReferenceRunEngine;
import tech.kayys.wayang.api.run.RunEngine;
import tech.kayys.wayang.api.WayangApi;
import tech.kayys.wayang.runtime.WaitRegistry;
import tech.kayys.wayang.store.jdbc.JdbcDefinitionStore;
import tech.kayys.wayang.store.jdbc.JdbcProjectStore;
import tech.kayys.wayang.store.jdbc.JdbcSchema;
import tech.kayys.wayang.store.jdbc.JdbcWaitRegistry;

/**
 * Wiring, selected by configuration:
 * <ul>
 * <li>{@code wayang.store=memory|jdbc} (default memory): definitions and wait tickets.
 * {@code jdbc} uses the default Quarkus datasource and creates the tables when
 * {@code wayang.jdbc.init-schema=true} (default).</li>
 * <li>{@code wayang.engine=reference|gamelan} (default reference): the in-memory
 * reference engine, or durable runs on Gamelan ({@code wayang.gamelan.endpoint},
 * {@code wayang.gamelan.api-key}).</li>
 * </ul>
 * The in-memory defaults lose all state on restart; use {@code jdbc} + {@code gamelan} for anything real.
 */
@ApplicationScoped
public class WayangBeans {

    @ConfigProperty(name = "wayang.store", defaultValue = "memory")
    String storeKind;
    @ConfigProperty(name = "wayang.engine", defaultValue = "reference")
    String engineKind;
    @ConfigProperty(name = "wayang.jdbc.init-schema", defaultValue = "true")
    boolean initSchema;
    @ConfigProperty(name = "wayang.gamelan.endpoint")
    Optional<String> gamelanEndpoint;
    @ConfigProperty(name = "wayang.gamelan.api-key")
    Optional<String> gamelanApiKey;

    @Inject
    Instance<DataSource> dataSource;
    @Inject
    Instance<WayangRuntime> runtime;

    private volatile boolean schemaReady;

    synchronized DataSource jdbc() {
        if (!dataSource.isResolvable()) {
            throw new IllegalStateException("wayang.store=jdbc needs a configured Quarkus datasource (quarkus.datasource.*)");
        }
        DataSource ds = dataSource.get();
        if (initSchema && !schemaReady) {
            JdbcSchema.apply(ds);
            schemaReady = true;
        }
        return ds;
    }

    @Produces
    @ApplicationScoped
    Clock clock() {
        return Clock.systemUTC();
    }

    @Produces
    @ApplicationScoped
    EventBus bus() {
        return new EventBus();
    }

    @Produces
    @ApplicationScoped
    DefinitionStore store() {
        return switch (storeKind) {
            case "memory" -> new DefinitionStore.InMemory();
            case "jdbc" -> new JdbcDefinitionStore(jdbc());
            default -> throw new IllegalStateException("wayang.store must be memory or jdbc, not '" + storeKind + "'");
        };
    }

    @Produces
    @ApplicationScoped
    WaitRegistry waits() {
        return switch (storeKind) {
            case "memory" -> new WaitRegistry.InMemory();
            case "jdbc" -> new JdbcWaitRegistry(jdbc());
            default -> throw new IllegalStateException("wayang.store must be memory or jdbc, not '" + storeKind + "'");
        };
    }

    @Produces
    @ApplicationScoped
    ProjectStore projects() {
        return switch (storeKind) {
            case "memory" -> new ProjectStore.InMemory();
            case "jdbc" -> new JdbcProjectStore(jdbc());
            default -> throw new IllegalStateException("wayang.store must be memory or jdbc, not '" + storeKind + "'");
        };
    }

    @Produces
    @ApplicationScoped
    RunEngine engine(DefinitionStore store, WaitRegistry waits, EventBus bus, Clock clock, OAuthConfig secrets,
            ConnectorsConfig connectors, EipConfig eip, ConfigsConfig configs, AuditConfig audit) {
        return switch (engineKind) {
            case "reference" -> {
                ReferenceRunEngine e = new ReferenceRunEngine(store, waits, connectors.all(), (spec, input) -> {
                    throw new IllegalStateException("No agent runtime configured: wire AndalusAgentInvoker");
                }, bus, clock);
                e.withEip(eip.idempotency(), eip.claims(), eip.aggregation());
                e.withMemory(eip.memory());
                if (secrets.resolver() != null) {
                    e.withSecrets(secrets.resolver());
                }
                e.withConfig(configs.service());
                if (audit.secretReadListener() != null) {
                    e.withSecretAccessListener(audit.secretReadListener());
                }
                yield e;
            }
            case "gamelan" -> new GamelanRunEngine(
                    new GamelanClients(gamelanEndpoint.orElseThrow(() -> new IllegalStateException("wayang.gamelan.endpoint is required")),
                            gamelanApiKey.orElse(null), Duration.ofSeconds(30)),
                    runtime.get(), bus, clock);
            default -> throw new IllegalStateException("wayang.engine must be reference or gamelan, not '" + engineKind + "'");
        };
    }

    @Produces
    @ApplicationScoped
    WayangApi api(DefinitionStore store, ProjectStore projects, RunEngine engine, WaitRegistry waits, EventBus bus,
            Clock clock) {
        return new WayangApi(store, projects, engine, waits, bus, clock);
    }

    @Produces
    @ApplicationScoped
    tech.kayys.wayang.api.trigger.TriggerStore triggerStore() {
        return switch (storeKind) {
            case "memory" -> new tech.kayys.wayang.api.trigger.TriggerStore.InMemory();
            case "jdbc" -> new tech.kayys.wayang.store.jdbc.JdbcTriggerStore(jdbc());
            default -> throw new IllegalStateException("wayang.store must be memory or jdbc, not '" + storeKind + "'");
        };
    }

    @Produces
    @ApplicationScoped
    tech.kayys.wayang.api.trigger.TriggerService triggers(tech.kayys.wayang.api.trigger.TriggerStore store, WayangApi api,
            ConnectorsConfig connectors, OAuthConfig secrets, Clock clock, TenantGateConfig gate, FeedbackConfig feedback) {
        var service = new tech.kayys.wayang.api.trigger.TriggerService(store, api, connectors.pollers(), connectors.webhookProviders(),
                secrets.resolver(), clock).withTenantGate(gate.gate()).withFeedback(feedback.service());
        // with a database the per-chat limits (hourly and daily caps) survive a restart; in memory mode they are per process like everything else
        return "jdbc".equals(storeKind) ? service.withRateJournal(new tech.kayys.wayang.store.jdbc.JdbcRateJournal(jdbc())) : service;
    }

    /** Hard deadlines for {@code collect} groups with {@code sweepTo}; ticked by {@link TimerTicker}. */
    @Produces
    @ApplicationScoped
    tech.kayys.wayang.api.run.CollectSweepService collectSweeper(DefinitionStore store, WayangApi api, EipConfig eip, Clock clock, TenantGateConfig gate) {
        return new tech.kayys.wayang.api.run.CollectSweepService(store, api, eip.aggregation(), clock,
                m -> java.util.logging.Logger.getLogger("wayang.collect").warning(m)).withTenantGate(gate.gate());
    }
}
