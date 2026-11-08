package tech.kayys.wayang.runtime;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Startup boundary that assembles the selected component closure and its executable bindings.
 *
 * <p>This class only activates trusted extensions already visible to the supplied class loader.
 * It does not download artifacts, mutate class paths, or hot-load arbitrary plugin code.</p>
 */
public final class ComponentRuntimeAssembly {

    private ComponentRuntimeAssembly() {
    }

    /** Discover profile-enabled extensions and activate the requested component closure. */
    public static RuntimeComponents activate(Profile profile, ClassLoader classLoader,
            Collection<String> requestedComponentIds) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(classLoader, "classLoader");
        Extensions extensions = Extensions.load(classLoader, profile);
        return activate(profile, extensions, requestedComponentIds);
    }

    /** Activate using an already constructed extension set (useful for embedded runtimes and tests). */
    public static RuntimeComponents activate(Profile profile, Extensions extensions,
            Collection<String> requestedComponentIds) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(extensions, "extensions");
        Objects.requireNonNull(requestedComponentIds, "requestedComponentIds");
        ComponentRegistry registry = ComponentRegistry.standard(profile, extensions);
        ComponentExecutorCatalog catalog = ComponentExecutorCatalog.resolve(
                registry, requestedComponentIds, extensions);
        return new RuntimeComponents(profile, extensions.ids(), catalog);
    }

    /** Immutable activated view consumed by runtime dispatch and diagnostics. */
    public record RuntimeComponents(Profile profile, List<String> extensionIds,
            ComponentExecutorCatalog catalog) {
        public RuntimeComponents {
            Objects.requireNonNull(profile, "profile");
            extensionIds = List.copyOf(extensionIds);
            Objects.requireNonNull(catalog, "catalog");
            if (catalog.profile() != profile) {
                throw new IllegalArgumentException("Activated component catalog profile does not match runtime profile");
            }
        }

        public ComponentExecutorRegistry executors() {
            return catalog.executors();
        }

        public List<String> componentIds() {
            return catalog.componentIds();
        }
    }
}
