package tech.kayys.wayang.adapter.gamelan;

import java.util.Map;

import io.smallrye.mutiny.Uni;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinition;
import tech.kayys.gamelan.sdk.client.GamelanClient;
import tech.kayys.wayang.bpmn.ExecutionPlan;
import tech.kayys.wayang.bpmn.ProcessCompiler;
import tech.kayys.wayang.bpmn.ProcessModel;

/** Thin facade: deploy a Wayang process to Gamelan and start instances of it. */
public final class WayangGamelanClient {

    private final GamelanClient gamelan;
    private final String tenantId;

    public WayangGamelanClient(GamelanClient gamelan, String tenantId) {
        this.gamelan = gamelan;
        this.tenantId = tenantId;
    }

    /** Validates, compiles and registers the process; fails with {@link ProcessCompiler.InvalidProcessException} on errors. */
    public Uni<WorkflowDefinition> deploy(ProcessModel model, String version) {
        ExecutionPlan plan = ProcessCompiler.compile(model);
        return gamelan.workflows().create(GamelanPlanMapper.toDefinition(plan, tenantId, version));
    }

    /** Creates and starts a run; the result's id identifies the instance. */
    public Uni<?> start(String processId, Map<String, Object> inputs) {
        return gamelan.runs().create(processId).inputs(inputs).executeAndStart();
    }
}
