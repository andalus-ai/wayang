package tech.kayys.wayang.rest;

import java.util.function.Function;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import tech.kayys.wayang.adapter.gamelan.StoreBackedComponents;
import tech.kayys.wayang.adapter.gamelan.WayangComponents;
import tech.kayys.wayang.agent.AgentSpec;
import tech.kayys.wayang.api.definition.DefinitionStore;
import tech.kayys.wayang.dmn.DecisionModel;
import tech.kayys.wayang.runtime.WaitRegistry;

/**
 * What the Gamelan executors in this JVM use: decisions/agents from the
 * definition store, and the same wait registry the API lists tasks from.
 */
@ApplicationScoped
public class ApiComponents implements WayangComponents {

    @Inject
    DefinitionStore store;
    @Inject
    WaitRegistry waits;
    @Inject
    OAuthConfig secrets;
    @Inject
    ConfigsConfig configs;
    @Inject
    AuditConfig audit;
    @Inject
    ConnectorsConfig connectors;

    @Inject
    EipConfig eip;

    private volatile StoreBackedComponents delegate;

    private StoreBackedComponents delegate() {
        StoreBackedComponents d = delegate;
        if (d == null) {
            d = delegate = new StoreBackedComponents(store);
        }
        return d;
    }

    @Override
    public Function<String, DecisionModel> decisionLookup() {
        return delegate().decisionLookup();
    }

    @Override
    public Function<String, AgentSpec> agentLookup() {
        return delegate().agentLookup();
    }

    @Override
    public WaitRegistry waitRegistry() {
        return waits;
    }

    @Override
    public tech.kayys.wayang.runtime.SecretResolver secretResolver() {
        return secrets.resolver();
    }

    @Override
    public tech.kayys.wayang.runtime.SecretAccessListener secretAccessListener() {
        return audit.secretReadListener();
    }

    @Override
    public tech.kayys.wayang.runtime.ConfigResolver configResolver() {
        return configs.service();
    }

    @Override
    public java.util.Map<String, tech.kayys.wayang.runtime.ServiceHandler.Connector> connectors() {
        return connectors.all();
    }

    @Override
    public tech.kayys.wayang.eip.IdempotencyStore idempotencyStore() {
        return eip.idempotency();
    }

    @Override
    public tech.kayys.wayang.eip.ClaimStore claimStore() {
        return eip.claims();
    }

    @Override
    public tech.kayys.wayang.agent.ConversationMemory conversationMemory() {
        return eip.memory();
    }

    @Override
    public tech.kayys.wayang.eip.AggregationStore aggregationStore() {
        return eip.aggregation();
    }
}
