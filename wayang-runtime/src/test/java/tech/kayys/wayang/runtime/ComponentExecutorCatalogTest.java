package tech.kayys.wayang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ComponentExecutorCatalogTest {

    private static final String ROOT = "test.root.component";
    private static final String DEP = "test.base.component";

    private ComponentDescriptor descriptor(String id, String version, String executorType,
            List<ComponentDescriptor.Dependency> dependencies) {
        return new ComponentDescriptor(id, version, ComponentDescriptor.Kind.NODE, executorType, "",
                java.util.Set.of(), dependencies, ComponentDescriptor.IsolationLevel.IN_PROCESS,
                java.util.Set.of());
    }

    private ComponentExecutor executor(String id) {
        return new ComponentExecutor() {
            @Override public String executorId() { return id; }
            @Override public ComponentExecutionResult execute(ExecutionRequest request) {
                return new ComponentExecutionResult.Completed(Map.of("ok", true));
            }
        };
    }

    @Test
    void resolvesDependencyClosureAndBindsExactExecutors() {
        ComponentRegistry registry = new ComponentRegistry(List.of(
                descriptor(ROOT, "1.0", "test.root", List.of(new ComponentDescriptor.Dependency(DEP, "1.0"))),
                descriptor(DEP, "1.0", "test.base", List.of())), Profile.FULL);

        ComponentExecutorCatalog catalog = ComponentExecutorCatalog.resolve(registry, List.of(ROOT), List.of(
                new ComponentExecutorCatalog.ComponentExecutorBinding(ROOT, "1.0", executor("test.root")),
                new ComponentExecutorCatalog.ComponentExecutorBinding(DEP, "1.0", executor("test.base"))));

        assertEquals(List.of(DEP, ROOT), catalog.componentIds());
        assertTrue(catalog.executors().contains(ROOT));
        assertTrue(catalog.executors().contains(DEP));
    }

    @Test
    void rejectsMissingExecutorForDependency() {
        ComponentRegistry registry = new ComponentRegistry(List.of(
                descriptor(ROOT, "1.0", "test.root", List.of(new ComponentDescriptor.Dependency(DEP, "1.0"))),
                descriptor(DEP, "1.0", "test.base", List.of())), Profile.FULL);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ComponentExecutorCatalog.resolve(registry, List.of(ROOT), List.of(
                        new ComponentExecutorCatalog.ComponentExecutorBinding(ROOT, "1.0", executor("test.root")))));
        assertTrue(error.getMessage().contains(DEP));
    }

    @Test
    void rejectsVersionAndExecutorFamilyMismatch() {
        ComponentRegistry registry = new ComponentRegistry(List.of(
                descriptor(ROOT, "1.0", "test.root", List.of())), Profile.FULL);

        assertThrows(IllegalArgumentException.class, () -> ComponentExecutorCatalog.resolve(
                registry, List.of(ROOT), List.of(new ComponentExecutorCatalog.ComponentExecutorBinding(
                        ROOT, "2.0", executor("test.root")))));
        assertThrows(IllegalArgumentException.class, () -> ComponentExecutorCatalog.resolve(
                registry, List.of(ROOT), List.of(new ComponentExecutorCatalog.ComponentExecutorBinding(
                        ROOT, "1.0", executor("wrong.family")))));
    }

    @Test
    void rejectsDuplicateAndUnselectedBindings() {
        ComponentRegistry registry = new ComponentRegistry(List.of(
                descriptor(ROOT, "1.0", "test.root", List.of()),
                descriptor(DEP, "1.0", "test.base", List.of())), Profile.FULL);
        var binding = new ComponentExecutorCatalog.ComponentExecutorBinding(ROOT, "1.0", executor("test.root"));

        assertThrows(IllegalArgumentException.class, () -> ComponentExecutorCatalog.resolve(
                registry, List.of(ROOT), List.of(binding, binding)));
        assertThrows(IllegalArgumentException.class, () -> ComponentExecutorCatalog.resolve(
                registry, List.of(ROOT), List.of(binding,
                        new ComponentExecutorCatalog.ComponentExecutorBinding(DEP, "1.0", executor("test.base")))));
    }

    @Test
    void rejectsComponentsUnavailableInProfile() {
        ComponentDescriptor agent = new ComponentDescriptor(ROOT, "1.0", ComponentDescriptor.Kind.AGENT,
                "test.agent", "", java.util.Set.of(Profile.Feature.AGENT), List.of(),
                ComponentDescriptor.IsolationLevel.IN_PROCESS, java.util.Set.of());
        ComponentRegistry registry = new ComponentRegistry(List.of(agent), Profile.AUTOMATION);

        assertThrows(ComponentRegistry.ComponentResolutionException.class,
                () -> ComponentExecutorCatalog.resolve(registry, List.of(ROOT), List.of()));
    }
}
