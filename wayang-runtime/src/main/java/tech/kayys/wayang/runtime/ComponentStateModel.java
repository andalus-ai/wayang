package tech.kayys.wayang.runtime;

/** State and placement guarantees required by a component implementation. */
public enum ComponentStateModel {
    /** No component-owned state between invocations. */
    STATELESS,
    /** State is persisted outside the worker and can be accessed by another worker. */
    EXTERNALIZED,
    /** State is local to an execution slot and requires affinity-aware placement. */
    AFFINE
}
