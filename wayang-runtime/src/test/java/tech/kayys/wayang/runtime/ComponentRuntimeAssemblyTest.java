package tech.kayys.wayang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ComponentRuntimeAssemblyTest {

    private static final String COMPONENT = "test.runtime.echo";

    private ComponentExecutor executor() {
        return new ComponentExecutor() {
            @Override public String executorId() { return "test.runtime"; }
            @Override public ComponentExecutionResult execute(ExecutionRequest request) {
                return new ComponentExecutionResult.Completed(Map.of("echo", request.inputs()));
            }
        };
    }

    private WayangExtension extension(boolean withExecutor) {
        ComponentDescriptor descriptor = new ComponentDescriptor(COMPONENT, "1.2.0",
                ComponentDescriptor.Kind.NODE, "test.runtime", "test component",
                Set.of(), List.of(), ComponentDescriptor.IsolationLevel.IN_PROCESS, Set.of());
        return new WayangExtension() {
            @Override public String id() { return "test-runtime-extension"; }
            @Override public List<ComponentDescriptor> components() { return List.of(descriptor); }
            @Override public List<ComponentExecutorCatalog.ComponentExecutorBinding> componentExecutors() {
                return withExecutor ? List.of(new ComponentExecutorCatalog.ComponentExecutorBinding(
                        COMPONENT, "1.2.0", executor())) : List.of();
            }
        };
    }

    @Test
    void activatesExtensionComponentAndExposesExecutorRegistry() {
        Extensions extensions = Extensions.of(List.of(extension(true)), Profile.FULL);

        var runtime = ComponentRuntimeAssembly.activate(Profile.FULL, extensions, List.of(COMPONENT));

        assertEquals(List.of("test-runtime-extension"), runtime.extensionIds());
        assertEquals(List.of(COMPONENT), runtime.componentIds());
        assertTrue(runtime.executors().contains(COMPONENT));
        var result = runtime.executors().execute(new ExecutionRequest("run-1", COMPONENT, "1.2.0",
                "execute", Map.of("message", "hello"), Map.of(), ExecutionMetadata.empty()));
        assertInstanceOf(ComponentExecutionResult.Completed.class, result);
    }

    @Test
    void failsActivationWhenSelectedComponentHasNoExecutor() {
        Extensions extensions = Extensions.of(List.of(extension(false)), Profile.FULL);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ComponentRuntimeAssembly.activate(Profile.FULL, extensions, List.of(COMPONENT)));

        assertTrue(error.getMessage().contains(COMPONENT));
    }

    @Test
    void doesNotActivateExtensionsExcludedByProfile() {
        WayangExtension agentOnly = new WayangExtension() {
            @Override public String id() { return "agent-only"; }
            @Override public Set<Profile.Feature> requires() { return Set.of(Profile.Feature.AGENT); }
            @Override public List<ComponentDescriptor> components() {
                return List.of(new ComponentDescriptor(COMPONENT, "1.2.0",
                        ComponentDescriptor.Kind.AGENT, "test.runtime", "agent",
                        Set.of(Profile.Feature.AGENT), List.of(),
                        ComponentDescriptor.IsolationLevel.IN_PROCESS, Set.of()));
            }
            @Override public List<ComponentExecutorCatalog.ComponentExecutorBinding> componentExecutors() {
                return List.of(new ComponentExecutorCatalog.ComponentExecutorBinding(
                        COMPONENT, "1.2.0", executor()));
            }
        };
        Extensions extensions = Extensions.of(List.of(agentOnly), Profile.AUTOMATION);

        assertThrows(ComponentRegistry.ComponentResolutionException.class,
                () -> ComponentRuntimeAssembly.activate(Profile.AUTOMATION, extensions, List.of(COMPONENT)));
    }

    @Test
    void rejectsExtensionExecutorVersionMismatch() {
        WayangExtension mismatch = new WayangExtension() {
            @Override public String id() { return "mismatch"; }
            @Override public List<ComponentDescriptor> components() {
                return List.of(new ComponentDescriptor(COMPONENT, "1.2.0",
                        ComponentDescriptor.Kind.NODE, "test.runtime", "test",
                        Set.of(), List.of(), ComponentDescriptor.IsolationLevel.IN_PROCESS, Set.of()));
            }
            @Override public List<ComponentExecutorCatalog.ComponentExecutorBinding> componentExecutors() {
                return List.of(new ComponentExecutorCatalog.ComponentExecutorBinding(
                        COMPONENT, "9.9.9", executor()));
            }
        };
        Extensions extensions = Extensions.of(List.of(mismatch), Profile.FULL);

        assertThrows(IllegalArgumentException.class,
                () -> ComponentRuntimeAssembly.activate(Profile.FULL, extensions, List.of(COMPONENT)));
    }
}
