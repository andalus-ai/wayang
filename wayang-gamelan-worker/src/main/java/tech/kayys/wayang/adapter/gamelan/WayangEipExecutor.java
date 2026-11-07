package tech.kayys.wayang.adapter.gamelan;

import io.quarkus.arc.lookup.LookupIfProperty;
import jakarta.enterprise.context.ApplicationScoped;
import tech.kayys.gamelan.sdk.executor.core.Executor;

/** Gamelan executor `wayang-eip`: Enterprise Integration Pattern pipelines. */
@ApplicationScoped
@LookupIfProperty(name = "wayang.worker.eip.enabled", stringValue = "true", lookupIfMissing = true)
@Executor(executorType = "wayang-eip", supportedNodeTypes = { "TASK" }, maxConcurrentTasks = 50,
        description = "Enterprise Integration Pattern pipelines")
public class WayangEipExecutor extends WayangNodeExecutor {
}
