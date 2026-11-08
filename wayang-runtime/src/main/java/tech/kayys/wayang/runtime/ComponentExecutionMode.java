package tech.kayys.wayang.runtime;

/** Execution locations a component implementation explicitly supports. */
public enum ComponentExecutionMode {
    /** Execute in the Wayang runtime process. */
    EMBEDDED,
    /** Execute in a separate process or local container on the same host. */
    ISOLATED_LOCAL,
    /** Execute through a registered remote worker. */
    REMOTE,
    /** Execute in a managed worker pool or orchestrated workload. */
    CLOUD_NATIVE
}
