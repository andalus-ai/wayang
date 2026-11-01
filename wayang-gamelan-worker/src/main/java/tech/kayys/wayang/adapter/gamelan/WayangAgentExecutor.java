package tech.kayys.wayang.adapter.gamelan;

import jakarta.enterprise.context.ApplicationScoped;
import tech.kayys.gamelan.sdk.executor.core.Executor;

/** Gamelan executor `wayang-agent`: AI agent steps delegated to Andalus. */
@ApplicationScoped
@Executor(executorType = "wayang-agent", supportedNodeTypes = { "TASK" }, maxConcurrentTasks = 50,
        description = "AI agent steps delegated to Andalus")
public class WayangAgentExecutor extends WayangNodeExecutor {
}
