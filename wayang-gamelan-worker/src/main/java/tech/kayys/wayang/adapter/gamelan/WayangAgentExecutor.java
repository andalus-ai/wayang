package tech.kayys.wayang.adapter.gamelan;

import io.quarkus.arc.lookup.LookupIfProperty;
import jakarta.enterprise.context.ApplicationScoped;
import tech.kayys.gamelan.sdk.executor.core.Executor;

/** Gamelan executor `wayang-agent`: AI agent steps delegated to Andalus. */
@ApplicationScoped
@LookupIfProperty(name = "wayang.worker.agent.enabled", stringValue = "true", lookupIfMissing = true)
@Executor(executorType = "wayang-agent", supportedNodeTypes = { "TASK" }, maxConcurrentTasks = 50,
        description = "AI agent steps delegated to Andalus")
public class WayangAgentExecutor extends WayangNodeExecutor {
}
