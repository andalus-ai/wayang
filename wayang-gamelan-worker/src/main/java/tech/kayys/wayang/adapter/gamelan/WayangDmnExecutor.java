package tech.kayys.wayang.adapter.gamelan;

import jakarta.enterprise.context.ApplicationScoped;
import tech.kayys.gamelan.sdk.executor.core.Executor;

/** Gamelan executor `wayang-dmn`: DMN decision evaluation. */
@ApplicationScoped
@Executor(executorType = "wayang-dmn", supportedNodeTypes = { "TASK" }, maxConcurrentTasks = 50,
        description = "DMN decision evaluation")
public class WayangDmnExecutor extends WayangNodeExecutor {
}
