package tech.kayys.wayang.adapter.gamelan;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import tech.kayys.wayang.agent.AgentSpec;
import tech.kayys.wayang.agent.AgentSpecLoader;
import tech.kayys.wayang.api.DefinitionKind;
import tech.kayys.wayang.api.DefinitionStore;
import tech.kayys.wayang.api.StoredDefinition;
import tech.kayys.wayang.dmn.DecisionModel;
import tech.kayys.wayang.dmn.DmnLoader;

/**
 * Resolves decisions and agents for the Gamelan executors from the
 * {@link DefinitionStore}, using tenant-qualified ids (see
 * {@link TenantQualifier}). Always the <em>latest published</em> version at
 * execution time, so publishing a new decision affects runs already in flight.
 * Parsed models are cached per (tenant, id, version).
 */
public final class StoreBackedComponents implements WayangComponents {

    private record Key(String tenant, String id, int version) {
    }

    private final DefinitionStore store;
    private final Map<Key, DecisionModel> decisions = new ConcurrentHashMap<>();
    private final Map<Key, AgentSpec> agents = new ConcurrentHashMap<>();

    public StoreBackedComponents(DefinitionStore store) {
        this.store = store;
    }

    @Override
    public Function<String, DecisionModel> decisionLookup() {
        return ref -> resolve(ref, DefinitionKind.DECISION, decisions, d -> DmnLoader.load(tech.kayys.wayang.api.Studio.executable(d.document())));
    }

    @Override
    public Function<String, AgentSpec> agentLookup() {
        return ref -> resolve(ref, DefinitionKind.AGENT, agents, d -> AgentSpecLoader.fromMap(tech.kayys.wayang.api.Studio.executable(d.document())));
    }

    private <T> T resolve(String ref, DefinitionKind kind, Map<Key, T> cache, Function<StoredDefinition, T> parse) {
        String[] parts = TenantQualifier.split(ref);
        if (parts == null) {
            return null;
        }
        return store.getLatest(parts[0], kind, parts[1]).map(d -> {
            Key k = new Key(parts[0], parts[1], d.version());
            T cached = cache.get(k);
            if (cached == null) {
                cached = parse.apply(d);
                cache.put(k, cached);
            }
            return cached;
        }).orElse(null);
    }
}
