package tech.kayys.wayang.adapter.gamelan;

import io.quarkus.arc.lookup.LookupIfProperty;
import jakarta.enterprise.context.ApplicationScoped;
import tech.kayys.gamelan.sdk.executor.core.Executor;

/** Gamelan executor `wayang-dmn`: DMN decision evaluation. */
@ApplicationScoped
@LookupIfProperty(name = "wayang.worker.dmn.enabled", stringValue = "true", lookupIfMissing = true)
@Executor(executorType = "wayang-dmn", supportedNodeTypes = { "TASK" }, maxConcurrentTasks = 50,
        description = "DMN decision evaluation")
public class WayangDmnExecutor extends WayangNodeExecutor {
}
