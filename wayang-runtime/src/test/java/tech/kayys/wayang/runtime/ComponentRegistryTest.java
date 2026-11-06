package tech.kayys.wayang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class ComponentRegistryTest {

    private static ComponentDescriptor component(
            String id,
            String version,
            Set<Profile.Feature> features,
            List<ComponentDescriptor.Dependency> dependencies) {
        return new ComponentDescriptor(
                id,
                version,
                ComponentDescriptor.Kind.NODE,
                "wayang-eip",
                id,
                features,
                dependencies,
                ComponentDescriptor.IsolationLevel.IN_PROCESS,
                Set.of("workflow.node"));
    }

    @Test
    void resolvesExactDependencyClosureInDependencyFirstOrder() {
        ComponentDescriptor connector = component("wayang.connector.http", "2.1.0", Set.of(), List.of());
        ComponentDescriptor trigger = component("wayang.eip.http-trigger", "1.3.0", Set.of(Profile.Feature.EIP),
                List.of(new ComponentDescriptor.Dependency("wayang.connector.http", "2.1.0")));
        ComponentRegistry registry = new ComponentRegistry(List.of(trigger, connector), Profile.INTEGRATION);

        assertEquals(List.of("wayang.connector.http", "wayang.eip.http-trigger"),
                registry.resolve(List.of("wayang.eip.http-trigger")).stream()
                        .map(ComponentDescriptor::id).toList());
        assertEquals(2, registry.all().size());
    }

    @Test
    void filtersComponentsThatRequireDisabledFeatures() {
        ComponentDescriptor agent = component("wayang.agent.invoke", "1.0.0",
                Set.of(Profile.Feature.AGENT), List.of());
        ComponentRegistry registry = new ComponentRegistry(List.of(agent), Profile.INTEGRATION);

        assertNull(registry.find("wayang.agent.invoke"));
        assertThrows(ComponentRegistry.ComponentResolutionException.class,
                () -> registry.resolve(List.of("wayang.agent.invoke")));
    }

    @Test
    void rejectsMissingAndVersionMismatchedDependencies() {
        ComponentDescriptor dependent = component("wayang.eip.router", "1.0.0", Set.of(),
                List.of(new ComponentDescriptor.Dependency("wayang.connector.http", "2.0.0")));
        ComponentRegistry missing = new ComponentRegistry(List.of(dependent), Profile.FULL);
        assertTrue(assertThrows(ComponentRegistry.ComponentResolutionException.class,
                () -> missing.resolve(List.of("wayang.eip.router"))).getMessage().contains("unavailable"));

        ComponentDescriptor wrongVersion = component("wayang.connector.http", "2.1.0", Set.of(), List.of());
        ComponentRegistry mismatch = new ComponentRegistry(List.of(dependent, wrongVersion), Profile.FULL);
        assertTrue(assertThrows(ComponentRegistry.ComponentResolutionException.class,
                () -> mismatch.resolve(List.of("wayang.eip.router"))).getMessage().contains("provides 2.1.0"));
    }

    @Test
    void rejectsDuplicateIdsAndDependencyCycles() {
        ComponentDescriptor a = component("wayang.eip.a", "1.0.0", Set.of(),
                List.of(new ComponentDescriptor.Dependency("wayang.eip.b", "1.0.0")));
        ComponentDescriptor b = component("wayang.eip.b", "1.0.0", Set.of(),
                List.of(new ComponentDescriptor.Dependency("wayang.eip.a", "1.0.0")));
        assertThrows(IllegalStateException.class,
                () -> new ComponentRegistry(List.of(a, component("wayang.eip.a", "2.0.0", Set.of(), List.of())),
                        Profile.FULL));
        ComponentRegistry registry = new ComponentRegistry(List.of(a, b), Profile.FULL);
        assertTrue(assertThrows(ComponentRegistry.ComponentResolutionException.class,
                () -> registry.resolve(List.of("wayang.eip.a"))).getMessage().contains("cycle"));
    }

    @Test
    void descriptorDefensivelyCopiesCollections() {
        var features = new java.util.HashSet<Profile.Feature>();
        features.add(Profile.Feature.EIP);
        var dependencies = new java.util.ArrayList<ComponentDescriptor.Dependency>();
        var capabilities = new java.util.HashSet<String>();
        capabilities.add("workflow.node");
        ComponentDescriptor descriptor = new ComponentDescriptor("wayang.eip.router", "1.0.0",
                ComponentDescriptor.Kind.NODE, "wayang-eip", "", features, dependencies,
                ComponentDescriptor.IsolationLevel.IN_PROCESS, capabilities);
        features.clear();
        capabilities.clear();
        assertEquals(Set.of(Profile.Feature.EIP), descriptor.requiredFeatures());
        assertEquals(Set.of("workflow.node"), descriptor.capabilities());
        assertThrows(UnsupportedOperationException.class, () -> descriptor.requiredFeatures().clear());
    }
}
