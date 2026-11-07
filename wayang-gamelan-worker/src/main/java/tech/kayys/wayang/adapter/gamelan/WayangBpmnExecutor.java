package tech.kayys.wayang.adapter.gamelan;

import io.quarkus.arc.lookup.LookupIfProperty;
import jakarta.enterprise.context.ApplicationScoped;
import tech.kayys.gamelan.sdk.executor.core.Executor;

/** Gamelan executor `wayang-bpmn`: BPMN control flow: events and gateways, timers, message catches. */
@ApplicationScoped
@LookupIfProperty(name = "wayang.worker.bpmn.enabled", stringValue = "true", lookupIfMissing = true)
@Executor(executorType = "wayang-bpmn", supportedNodeTypes = { "TASK" }, maxConcurrentTasks = 50,
        description = "BPMN control flow: events and gateways, timers, message catches")
public class WayangBpmnExecutor extends WayangNodeExecutor {
}
