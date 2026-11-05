package tech.kayys.wayang.adapter.gamelan;

import java.util.Map;
import java.util.function.Function;

import tech.kayys.wayang.agent.AgentInvoker;
import tech.kayys.wayang.agent.AgentSpec;
import tech.kayys.wayang.dmn.DecisionModel;
import tech.kayys.wayang.embed.ReleaseDefinitions;
import tech.kayys.wayang.embed.ReleaseEngine;
import tech.kayys.wayang.runtime.ConfigResolver;
import tech.kayys.wayang.runtime.SecretResolver;
import tech.kayys.wayang.runtime.ServiceHandler;

/**
 * What the Wayang executors of a release use: the decisions and agents of the bundle, the connectors the workflows call, and
 * secrets and settings from the environment. Decision and agent references arrive tenant-qualified ({@code release/<id>}) because
 * the definitions are deployed with {@link TenantQualifier}; a reference to any other tenant resolves to nothing.
 */
public final class ReleaseComponents implements WayangComponents {

    private final ReleaseDefinitions defs;
    private final Map<String, ServiceHandler.Connector> connectors;
    private final AgentInvoker invoker;
    private final SecretResolver secrets;
    private final ConfigResolver config;
    private final tech.kayys.wayang.agent.ConversationMemory memory;
    private final tech.kayys.wayang.eip.AggregationStore aggregation = new tech.kayys.wayang.eip.AggregationStore.InMemory(10_000);

    /** @param invoker null when the release has no agent tasks (agent nodes then fail with a clear error) */
    public ReleaseComponents(ReleaseDefinitions defs, Map<String, ServiceHandler.Connector> connectors, AgentInvoker invoker,
            SecretResolver secrets, ConfigResolver config) {
        this(defs, connectors, invoker, secrets, config, null);
    }

    /** @param memory what agents remember between runs (for example a file memory in {@code WAYANG_STATE_DIR}); null: in memory */
    public ReleaseComponents(ReleaseDefinitions defs, Map<String, ServiceHandler.Connector> connectors, AgentInvoker invoker,
            SecretResolver secrets, ConfigResolver config, tech.kayys.wayang.agent.ConversationMemory memory) {
        this.memory = memory != null ? memory : new tech.kayys.wayang.agent.ConversationMemory.InMemory(10_000);
        this.defs = defs;
        this.connectors = Map.copyOf(tech.kayys.wayang.embed.AgentActions.with(tech.kayys.wayang.runtime.Extensions.load(tech.kayys.wayang.runtime.Profile.fromEnvironment())
                .mergeConnectors(connectors == null ? Map.of() : connectors), secrets, config));
        this.invoker = invoker;
        this.secrets = secrets;
        this.config = config;
    }

    @Override
    public tech.kayys.wayang.agent.ConversationMemory conversationMemory() {
        return memory;
    }

    /** One store for the whole release, so the engine can sweep the groups the runtime fills. */
    @Override
    public tech.kayys.wayang.eip.AggregationStore aggregationStore() {
        return aggregation;
    }

    @Override
    public SecretResolver secretResolver() {
        return secrets;
    }

    @Override
    public ConfigResolver configResolver() {
        return config;
    }

    @Override
    public Function<String, DecisionModel> decisionLookup() {
        return ref -> lookup(ref, defs.decisions());
    }

    @Override
    public Function<String, AgentSpec> agentLookup() {
        return ref -> lookup(ref, defs.agents());
    }

    private static <T> T lookup(String ref, Map<String, T> from) {
        String[] parts = TenantQualifier.split(ref);
        return parts == null || !parts[0].equals(ReleaseEngine.TENANT) ? null : from.get(parts[1]);
    }

    @Override
    public Map<String, ServiceHandler.Connector> connectors() {
        return connectors;
    }

    @Override
    public AgentInvoker agentInvoker() {
        return invoker != null ? invoker : (spec, in) -> {
            throw new IllegalStateException("No agent runtime is configured in this release");
        };
    }
}
