package tech.kayys.wayang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class ExtensionsExecutionCapabilitiesTest {

    @Test
    void exposesCapabilitiesFromProfileEnabledExtensions() {
        var descriptor = new ComponentDescriptor(
                "wayang.extension.document", "1.0.0", ComponentDescriptor.Kind.NODE,
                "wayang-eip", "Document", Set.of(), List.of(),
                ComponentDescriptor.IsolationLevel.IN_PROCESS, Set.of());
        var declaration = new ComponentExecutionCapabilities(
                "wayang.extension.document", "1.0.0",
                Set.of(ComponentExecutionMode.EMBEDDED, ComponentExecutionMode.REMOTE),
                ComponentStateModel.STATELESS, true, true, false, null, Set.of("document.extract"), java.util.Map.of());

        WayangExtension extension = new WayangExtension() {
            @Override public String id() { return "document-extension"; }
            @Override public List<ComponentDescriptor> components() { return List.of(descriptor); }
            @Override public List<ComponentExecutionCapabilities> executionCapabilities() {
                return List.of(declaration);
            }
        };

        Extensions extensions = Extensions.of(List.of(extension), Profile.FULL);
        var components = ComponentRegistry.standard(Profile.FULL, extensions);
        var capabilities = ComponentExecutionCapabilityRegistry.of(components, extensions.executionCapabilities());

        assertTrue(capabilities.supports("wayang.extension.document", ComponentExecutionMode.REMOTE));
    }

    @Test
    void profileDisabledExtensionsDoNotContributeCapabilities() {
        WayangExtension extension = new WayangExtension() {
            @Override public String id() { return "agent-extension"; }
            @Override public Set<Profile.Feature> requires() { return Set.of(Profile.Feature.AGENT); }
            @Override public List<ComponentExecutionCapabilities> executionCapabilities() {
                return List.of(new ComponentExecutionCapabilities(
                        "wayang.agent.external", "1.0.0", Set.of(ComponentExecutionMode.REMOTE),
                        ComponentStateModel.STATELESS, true, true, false, null, Set.of(), java.util.Map.of()));
            }
        };

        assertTrue(Extensions.of(List.of(extension), Profile.INTEGRATION).executionCapabilities().isEmpty());
    }
}
