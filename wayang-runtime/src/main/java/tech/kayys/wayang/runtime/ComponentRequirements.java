package tech.kayys.wayang.runtime;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import tech.kayys.wayang.bpmn.ElementType;

/**
 * Maps the BPMN element vocabulary to reusable runtime component contracts.
 *
 * <p>This is the bridge between the process model and the deployable component
 * catalog. It deliberately maps domain element types to stable component IDs;
 * executor implementation details remain behind the runtime SPI.</p>
 */
public final class ComponentRequirements {
    private ComponentRequirements() {}

    public static String componentId(ElementType type) {
        return switch (type) {
            case START_EVENT, END_EVENT, EXCLUSIVE_GATEWAY, PARALLEL_GATEWAY,
                    INCLUSIVE_GATEWAY, TIMER_EVENT, MESSAGE_CATCH_EVENT -> "wayang.bpmn.control-flow";
            case SERVICE_TASK -> "wayang.service.connector";
            case BUSINESS_RULE_TASK -> "wayang.dmn.decision";
            case AGENT_TASK -> "wayang.agent.invoke";
            case EIP_TASK -> "wayang.eip.pipeline";
            case USER_TASK -> "wayang.human.task";
        };
    }

    /** Unique component roots, sorted so lockfile generation is reproducible. */
    public static List<String> componentIds(Collection<ElementType> elementTypes) {
        Set<String> ids = new TreeSet<>();
        if (elementTypes != null) {
            for (ElementType type : elementTypes) {
                if (type != null) ids.add(componentId(type));
            }
        }
        return List.copyOf(ids);
    }

    /** Resolve component roots plus their exact dependency closure for this profile. */
    public static List<ComponentDescriptor> resolve(Collection<ElementType> elementTypes, ComponentRegistry registry) {
        return registry.resolve(componentIds(elementTypes));
    }
}
