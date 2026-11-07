package tech.kayys.wayang.runtime;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable dispatch registry for neutral component executors.
 *
 * <p>Registry keys are component IDs, not executor-family names. This allows
 * multiple components to share an executor implementation while retaining
 * component-level version and policy boundaries.</p>
 */
public final class ComponentExecutorRegistry {

    private final Map<String, ComponentExecutor> executors;

    private ComponentExecutorRegistry(Map<String, ComponentExecutor> executors) {
        this.executors = Map.copyOf(executors);
    }

    public static ComponentExecutorRegistry empty() {
        return new ComponentExecutorRegistry(Map.of());
    }

    public static ComponentExecutorRegistry of(Map<String, ? extends ComponentExecutor> executors) {
        Objects.requireNonNull(executors, "executors");
        Map<String, ComponentExecutor> copy = new LinkedHashMap<>();
        executors.forEach((id, executor) -> {
            requireText(id, "componentId");
            Objects.requireNonNull(executor, "executor for " + id);
            if (copy.putIfAbsent(id, executor) != null) {
                throw new IllegalStateException("Duplicate component executor: " + id);
            }
        });
        return new ComponentExecutorRegistry(copy);
    }

    public static ComponentExecutorRegistry from(Collection<? extends ComponentExecutorBinding> bindings) {
        Objects.requireNonNull(bindings, "bindings");
        Map<String, ComponentExecutor> copy = new LinkedHashMap<>();
        for (ComponentExecutorBinding binding : bindings) {
            Objects.requireNonNull(binding, "binding");
            if (copy.putIfAbsent(binding.componentId(), binding.executor()) != null) {
                throw new IllegalStateException("Duplicate component executor: " + binding.componentId());
            }
        }
        return new ComponentExecutorRegistry(copy);
    }

    public ComponentExecutionResult execute(ExecutionRequest request) {
        Objects.requireNonNull(request, "request");
        ComponentExecutor executor = executors.get(request.componentId());
        if (executor == null) {
            return new ComponentExecutionResult.Rejected("EXECUTOR_NOT_FOUND",
                    "No executor registered for component '" + request.componentId() + "'");
        }
        return executor.execute(request);
    }

    public ComponentExecutor find(String componentId) {
        return executors.get(componentId);
    }

    public boolean contains(String componentId) {
        return executors.containsKey(componentId);
    }

    public Map<String, ComponentExecutor> executors() {
        return executors;
    }

    private static void requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
    }

    /** Explicit component-to-executor binding; component ID remains the dispatch key. */
    public record ComponentExecutorBinding(String componentId, ComponentExecutor executor) {
        public ComponentExecutorBinding {
            requireText(componentId, "componentId");
            Objects.requireNonNull(executor, "executor");
        }
    }
}
