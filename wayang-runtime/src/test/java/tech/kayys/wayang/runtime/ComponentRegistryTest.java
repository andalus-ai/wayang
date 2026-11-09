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
    void extensionsContributeMetadataWithoutChangingExecutionHandlers() {
        ComponentDescriptor descriptor = component("wayang.extension.sample", "1.0.0", Set.of(), List.of());
        WayangExtension extension = new WayangExtension() {
            @Override
            public String id() {
                return "sample";
            }

            @Override
            public List<ComponentDescriptor> components() {
                return List.of(descriptor);
            }
        };
        Extensions extensions = Extensions.of(List.of(extension), Profile.FULL);
        ComponentRegistry registry = ComponentRegistry.from(extensions, Profile.FULL);

        assertEquals(List.of("wayang.extension.sample"), registry.resolve(List.of("wayang.extension.sample"))
                .stream().map(ComponentDescriptor::id).toList());
    }

    @Test
    void standardCatalogContainsBuiltInRuntimeCapabilities() {
        ComponentRegistry registry = ComponentRegistry.standard(Profile.FULL);

        assertEquals(Set.of(
                "wayang.bpmn.control-flow",
                "wayang.service.connector",
                "wayang.eip.pipeline",
                "wayang.dmn.decision",
                "wayang.agent.invoke",
                "wayang.human.task"), registry.ids());
        assertEquals(BuiltInComponents.VERSION,
                registry.find("wayang.agent.invoke").version());
        assertEquals("wayang-agent",
                registry.find("wayang.agent.invoke").executorType());
    }

    @Test
    void standardCatalogRespectsFeatureProfiles() {
        ComponentRegistry automation = ComponentRegistry.standard(Profile.AUTOMATION);
        assertTrue(automation.ids().contains("wayang.bpmn.control-flow"));
        assertTrue(automation.ids().contains("wayang.service.connector"));
        assertTrue(automation.ids().contains("wayang.dmn.decision"));
        assertTrue(automation.ids().contains("wayang.human.task"));
        assertFalse(automation.ids().contains("wayang.eip.pipeline"));
        assertFalse(automation.ids().contains("wayang.agent.invoke"));

        ComponentRegistry integration = ComponentRegistry.standard(Profile.INTEGRATION);
        assertTrue(integration.ids().contains("wayang.eip.pipeline"));
        assertTrue(integration.ids().contains("wayang.service.connector"));
        assertFalse(integration.ids().contains("wayang.dmn.decision"));
        assertFalse(integration.ids().contains("wayang.agent.invoke"));

        ComponentRegistry agentic = ComponentRegistry.standard(Profile.AGENTIC);
        assertTrue(agentic.ids().contains("wayang.agent.invoke"));
        assertTrue(agentic.ids().contains("wayang.eip.pipeline"));
        assertFalse(agentic.ids().contains("wayang.dmn.decision"));
    }

    @Test
    void standardCatalogCombinesBuiltInsAndProfileEnabledExtensions() {
        ComponentDescriptor custom = component("wayang.acme.customer-lookup", "1.2.0",
                Set.of(Profile.Feature.SERVICE), List.of());
        WayangExtension extension = new WayangExtension() {
            @Override
            public String id() {
                return "acme";
            }

            @Override
            public List<ComponentDescriptor> components() {
                return List.of(custom);
            }
        };
        ComponentRegistry registry = ComponentRegistry.standard(
                Profile.AUTOMATION, Extensions.of(List.of(extension), Profile.AUTOMATION));

        assertNotNull(registry.find("wayang.service.connector"));
        assertEquals("1.2.0", registry.find("wayang.acme.customer-lookup").version());
    }

    @Test
    void extensionCannotOverrideBuiltInComponentId() {
        ComponentDescriptor override = component("wayang.agent.invoke", "9.9.9",
                Set.of(Profile.Feature.AGENT), List.of());
        WayangExtension extension = new WayangExtension() {
            @Override
            public String id() {
                return "untrusted-override";
            }

            @Override
            public List<ComponentDescriptor> components() {
                return List.of(override);
            }
        };
        Extensions extensions = Extensions.of(List.of(extension), Profile.FULL);

        assertThrows(IllegalStateException.class,
                () -> ComponentRegistry.standard(Profile.FULL, extensions));
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
