package tech.kayys.wayang.adapter.gamelan;

import jakarta.enterprise.context.ApplicationScoped;
import tech.kayys.gamelan.sdk.executor.core.Executor;

/** Gamelan executor `wayang-eip`: Enterprise Integration Pattern pipelines. */
@ApplicationScoped
@Executor(executorType = "wayang-eip", supportedNodeTypes = { "TASK" }, maxConcurrentTasks = 50,
        description = "Enterprise Integration Pattern pipelines")
public class WayangEipExecutor extends WayangNodeExecutor {
}
