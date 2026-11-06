package tech.kayys.wayang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import tech.kayys.wayang.bpmn.ElementType;

class ComponentRequirementsTest {
    @Test
    void mapsEveryProcessElementFamilyToAStableComponentId() {
        assertEquals("wayang.bpmn.control-flow", ComponentRequirements.componentId(ElementType.START_EVENT));
        assertEquals("wayang.bpmn.control-flow", ComponentRequirements.componentId(ElementType.TIMER_EVENT));
        assertEquals("wayang.service.connector", ComponentRequirements.componentId(ElementType.SERVICE_TASK));
        assertEquals("wayang.dmn.decision", ComponentRequirements.componentId(ElementType.BUSINESS_RULE_TASK));
        assertEquals("wayang.agent.invoke", ComponentRequirements.componentId(ElementType.AGENT_TASK));
        assertEquals("wayang.eip.pipeline", ComponentRequirements.componentId(ElementType.EIP_TASK));
        assertEquals("wayang.human.task", ComponentRequirements.componentId(ElementType.USER_TASK));
    }

    @Test
    void rootsAreUniqueAndSorted() {
        assertEquals(List.of("wayang.bpmn.control-flow", "wayang.service.connector"),
                ComponentRequirements.componentIds(List.of(ElementType.SERVICE_TASK, ElementType.START_EVENT,
                        ElementType.END_EVENT, ElementType.SERVICE_TASK)));
    }

    @Test
    void resolvesOnlyComponentsAvailableInTheSelectedProfile() {
        var full = ComponentRegistry.standard(Profile.FULL);
        var automation = ComponentRegistry.standard(Profile.AUTOMATION);
        assertEquals(List.of("wayang.bpmn.control-flow", "wayang.service.connector"),
                ComponentRequirements.resolve(List.of(ElementType.START_EVENT, ElementType.SERVICE_TASK), full)
                        .stream().map(ComponentDescriptor::id).toList());
        assertThrows(ComponentRegistry.ComponentResolutionException.class,
                () -> ComponentRequirements.resolve(List.of(ElementType.AGENT_TASK), automation));
    }
}
