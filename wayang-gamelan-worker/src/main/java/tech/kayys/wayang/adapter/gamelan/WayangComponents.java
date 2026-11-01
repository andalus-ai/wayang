package tech.kayys.wayang.adapter.gamelan;

import java.util.Map;

import tech.kayys.wayang.agent.AgentInvoker;
import tech.kayys.wayang.agent.AgentSpec;
import tech.kayys.wayang.dmn.DecisionModel;
import tech.kayys.wayang.eip.IdempotencyStore;
import tech.kayys.wayang.runtime.ServiceHandler;
import tech.kayys.wayang.runtime.WaitRegistry;

/**
 * What an application supplies to the Wayang executors. Provide a CDI bean
 * implementing this interface (e.g. backed by the Studio's project store);
 * defaults are empty so a bare deployment can still run control-flow-only
 * processes.
 */
public interface WayangComponents {

    /** Tenant-scoped secret lookup for {@code ${secret:NAME}}; null disables secret resolution. */
    default tech.kayys.wayang.runtime.SecretResolver secretResolver() {
        return null;
    }

    /** Tenant-scoped plain settings for {@code ${config:NAME}}; null disables config resolution. */
    default tech.kayys.wayang.runtime.ConfigResolver configResolver() {
        return null;
    }

    /** Told of every {@code ${secret:NAME}} a node resolves (name only); null disables. */
    default tech.kayys.wayang.runtime.SecretAccessListener secretAccessListener() {
        return null;
    }

    default Map<String, DecisionModel> decisionModels() {
        return Map.of();
    }

    default Map<String, AgentSpec> agents() {
        return Map.of();
    }

    /** On-demand lookup used by the executors; override for tenant-aware stores. Default reads {@link #decisionModels()}. */
    default java.util.function.Function<String, DecisionModel> decisionLookup() {
        return decisionModels()::get;
    }

    default java.util.function.Function<String, AgentSpec> agentLookup() {
        return agents()::get;
    }

    default Map<String, ServiceHandler.Connector> connectors() {
        return Map.of();
    }

    /** Runs agents; production wiring delegates to Andalus (see wayang-adapter-andalus). */
    default AgentInvoker agentInvoker() {
        return (spec, in) -> {
            throw new UnsupportedOperationException("No AgentInvoker configured; wire wayang-adapter-andalus");
        };
    }

    /** Use a durable, shared store for EFFECTIVELY_ONCE pipelines. */
    default IdempotencyStore idempotencyStore() {
        return new IdempotencyStore.InMemory(100_000);
    }

    /** Claim-check storage for pipelines; use a durable store when claims must outlive the process. */
    default tech.kayys.wayang.eip.ClaimStore claimStore() {
        return new tech.kayys.wayang.eip.ClaimStore.InMemory(10_000);
    }

    /** Group storage for the {@code collect} step; durable and shared when replicas must aggregate together. */
    /** What agents with a {@code memory} option remember; the default is per runtime, in memory. */
    default tech.kayys.wayang.agent.ConversationMemory conversationMemory() {
        return new tech.kayys.wayang.agent.ConversationMemory.InMemory(10_000);
    }

    default tech.kayys.wayang.eip.AggregationStore aggregationStore() {
        return new tech.kayys.wayang.eip.AggregationStore.InMemory(10_000);
    }

    default WaitRegistry waitRegistry() {
        return new WaitRegistry.InMemory();
    }
}
