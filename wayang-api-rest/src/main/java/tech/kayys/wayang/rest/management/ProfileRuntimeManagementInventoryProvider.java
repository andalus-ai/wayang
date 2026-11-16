package tech.kayys.wayang.rest.management;

import java.util.List;

import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import tech.kayys.wayang.management.client.ManagementContracts.CapabilityInput;
import tech.kayys.wayang.management.client.ManagementContracts.InventoryRequest;
import tech.kayys.wayang.runtime.ComponentDescriptor;
import tech.kayys.wayang.runtime.ComponentRegistry;
import tech.kayys.wayang.runtime.Extensions;
import tech.kayys.wayang.runtime.Profile;

/** Reports only components enabled by the same profile-filtered extension registry as the catalog. */
@ApplicationScoped
@DefaultBean
public class ProfileRuntimeManagementInventoryProvider implements RuntimeManagementInventoryProvider {
    @Override
    public InventoryRequest snapshot() {
        Profile profile = Profile.fromEnvironment();
        ComponentRegistry registry = ComponentRegistry.standard(profile, Extensions.load(profile));
        List<CapabilityInput> capabilities = registry.all().stream()
                .flatMap(component -> component.capabilities().stream().sorted()
                        .map(capability -> new CapabilityInput(component.id(), component.version(), capability)))
                .toList();
        // Workflow inventory is intentionally empty until a durable, authoritative workflow
        // deployment registry is wired. Never infer deployed workflows from the authoring catalog.
        return new InventoryRequest(capabilities, List.of());
    }
}
