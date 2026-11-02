package tech.kayys.wayang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import tech.kayys.wayang.bpmn.ElementType;
import tech.kayys.wayang.bpmn.ExecutionPlan;
import tech.kayys.wayang.bpmn.PlanNode;

class ProfileTest {

    private static PlanNode node(String id, ElementType t, String exec) {
        return new PlanNode(id, id, t, exec, Map.of(), List.of(), List.of());
    }

    private static ExecutionPlan plan(PlanNode... n) {
        return new ExecutionPlan("p", "p", List.of(n));
    }

    private static DomainHandler handler(String type) {
        return new DomainHandler() {
            public String executorType() {
                return type;
            }

            public NodeOutcome handle(String runId, PlanNode node, Map<String, Object> vars) {
                return new NodeOutcome.Completed(Map.of("ok", true));
            }
        };
    }

    @Test
    void namedProfilesAndCustomLists() {
        assertSame(Profile.FULL, Profile.parse(null));
        assertSame(Profile.FULL, Profile.parse("  "));
        assertSame(Profile.AUTOMATION, Profile.parse("Automation"));
        assertTrue(Profile.INTEGRATION.has(Profile.Feature.EIP));
        assertFalse(Profile.INTEGRATION.has(Profile.Feature.AGENT));
        assertFalse(Profile.AUTOMATION.has(Profile.Feature.AGENT));
        Profile c = Profile.parse("dmn, service,bpmn");
        assertEquals(Set.of(Profile.Feature.DMN, Profile.Feature.SERVICE), c.features());
        assertThrows(IllegalArgumentException.class, () -> Profile.parse("agnt"), "a typo must not silently enable everything");
    }

    @Test
    void planChecksNameTheOffendingNodes() {
        ExecutionPlan p = plan(node("s", ElementType.START_EVENT, "wayang-bpmn"), node("a", ElementType.AGENT_TASK, "wayang-agent"),
                node("d", ElementType.BUSINESS_RULE_TASK, "wayang-dmn"));
        assertTrue(Profile.FULL.violations(p).isEmpty());
        List<String> v = Profile.AUTOMATION.violations(p);
        assertEquals(1, v.size());
        assertTrue(v.get(0).contains("'a'") && v.get(0).contains("automation"), v.get(0));
        assertEquals(2, Profile.parse("service").violations(p).size());
        assertTrue(Profile.parse("service").allowsExecutor("my-custom-type"), "extension types are not a built-in feature");
    }

    @Test
    void runnerDropsDisabledHandlersAndExplainsWhy() {
        NodeRunner r = new NodeRunner(List.of(handler("wayang-bpmn"), handler("wayang-agent"), handler("wayang-dmn"))).withProfile(Profile.AUTOMATION);
        assertTrue(r.supports("wayang-dmn"));
        assertFalse(r.supports("wayang-agent"));
        NodeOutcome o = r.run("r", node("a", ElementType.AGENT_TASK, "wayang-agent"), Map.of());
        assertTrue(o instanceof NodeOutcome.Failed f && f.error().contains("'automation' profile"), String.valueOf(o));
        assertTrue(r.run("r", node("d", ElementType.BUSINESS_RULE_TASK, "wayang-dmn"), Map.of()) instanceof NodeOutcome.Completed);
    }

    static final class Ext implements WayangExtension {
        private final String id;
        private final Set<Profile.Feature> req;
        private final String name;

        Ext(String id, Set<Profile.Feature> req, String name) {
            this.id = id;
            this.req = req;
            this.name = name;
        }

        public String id() {
            return id;
        }

        public Set<Profile.Feature> requires() {
            return req;
        }

        public Map<String, ServiceHandler.Connector> connectors() {
            return Map.of(name, in -> Map.of("from", id));
        }

        public List<DomainHandler> handlers() {
            return List.of(handler("x-" + id));
        }
    }

    @Test
    void extensionsAreFilteredByProfileAndCannotClash() {
        Extensions e = Extensions.of(List.of(new Ext("a", Set.of(), "ca"), new Ext("b", Set.of(Profile.Feature.AGENT), "cb")), Profile.AUTOMATION);
        assertEquals(List.of("a"), e.ids());
        Map<String, ServiceHandler.Connector> m = e.mergeConnectors(Map.of("http", in -> Map.of()));
        assertEquals(Set.of("http", "ca"), m.keySet());
        assertThrows(IllegalStateException.class, () -> e.mergeConnectors(Map.of("ca", in -> Map.of())));
        assertThrows(IllegalStateException.class, () -> Extensions.of(List.of(new Ext("a", Set.of(), "x"), new Ext("a", Set.of(), "y")), Profile.FULL));
        NodeRunner r = new NodeRunner(List.of(handler("wayang-bpmn"))).withExtensions(e);
        assertTrue(r.supports("x-a"));
        assertFalse(r.supports("x-b"));
        assertThrows(IllegalArgumentException.class, () -> new NodeRunner(List.of(handler("x-a"))).withExtensions(e));
    }
}
