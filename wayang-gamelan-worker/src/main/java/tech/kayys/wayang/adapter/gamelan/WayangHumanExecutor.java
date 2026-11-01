package tech.kayys.wayang.adapter.gamelan;

import jakarta.enterprise.context.ApplicationScoped;
import tech.kayys.gamelan.sdk.executor.core.Executor;

/** Gamelan executor `wayang-human`: Human tasks (forms, approvals). */
@ApplicationScoped
@Executor(executorType = "wayang-human", supportedNodeTypes = { "TASK" }, maxConcurrentTasks = 50,
        description = "Human tasks (forms, approvals)")
public class WayangHumanExecutor extends WayangNodeExecutor {
}
