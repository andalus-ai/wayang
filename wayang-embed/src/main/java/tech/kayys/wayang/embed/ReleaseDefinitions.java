package tech.kayys.wayang.embed;

import java.util.LinkedHashMap;
import java.util.Map;

import tech.kayys.wayang.agent.AgentSpec;
import tech.kayys.wayang.agent.AgentSpecLoader;
import tech.kayys.wayang.bpmn.ExecutionPlan;
import tech.kayys.wayang.bpmn.ProcessCompiler;
import tech.kayys.wayang.bpmn.ProcessMaps;
import tech.kayys.wayang.dmn.DecisionModel;
import tech.kayys.wayang.dmn.DmnLoader;

/** The definitions of a release bundle, loaded and compiled once, the same way for every engine. */
public final class ReleaseDefinitions {

    private final Map<String, ExecutionPlan> plans = new LinkedHashMap<>();
    private final Map<String, Integer> versions = new LinkedHashMap<>();
    private final Map<String, DecisionModel> decisions = new LinkedHashMap<>();
    private final Map<String, AgentSpec> agents = new LinkedHashMap<>();

    /** @throws IllegalArgumentException when a process does not compile or a definition is invalid: a release that cannot run does not start */
    public ReleaseDefinitions(ReleaseBundle bundle) {
        for (ReleaseBundle.Definition d : bundle.definitions()) {
            Map<String, Object> doc = withoutStudio(d.document());
            switch (d.kind()) {
                case "PROCESS" -> {
                    try {
                        plans.put(d.id(), ProcessCompiler.compile(ProcessMaps.fromMap(doc)));
                    } catch (RuntimeException e) {
                        throw new IllegalArgumentException("Process " + d.id() + " does not compile: " + e.getMessage());
                    }
                    versions.put(d.id(), d.version());
                }
                case "DECISION" -> decisions.put(d.id(), DmnLoader.load(doc));
                case "AGENT" -> agents.put(d.id(), AgentSpecLoader.fromMap(doc));
                default -> {
                    // PIPELINE definitions are inline in the nodes that use them; nothing to register
                }
            }
        }
    }

    public Map<String, ExecutionPlan> plans() {
        return plans;
    }

    public Map<String, Integer> versions() {
        return versions;
    }

    public Map<String, DecisionModel> decisions() {
        return decisions;
    }

    public Map<String, AgentSpec> agents() {
        return agents;
    }

    /** The document without the Studio's layout. */
    public static Map<String, Object> withoutStudio(Map<String, Object> doc) {
        if (!doc.containsKey("studio")) {
            return doc;
        }
        Map<String, Object> copy = new LinkedHashMap<>(doc);
        copy.remove("studio");
        return copy;
    }
}
