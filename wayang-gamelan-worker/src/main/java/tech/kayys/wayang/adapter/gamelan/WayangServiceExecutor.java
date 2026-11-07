package tech.kayys.wayang.adapter.gamelan;

import io.quarkus.arc.lookup.LookupIfProperty;
import jakarta.enterprise.context.ApplicationScoped;
import tech.kayys.gamelan.sdk.executor.core.Executor;

/** Gamelan executor `wayang-service`: Connector calls. */
@ApplicationScoped
@LookupIfProperty(name = "wayang.worker.service.enabled", stringValue = "true", lookupIfMissing = true)
@Executor(executorType = "wayang-service", supportedNodeTypes = { "TASK" }, maxConcurrentTasks = 50,
        description = "Connector calls")
public class WayangServiceExecutor extends WayangNodeExecutor {
}
