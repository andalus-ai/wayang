package tech.kayys.wayang.deployment;

/** Durable command receipt states. UNKNOWN is terminal until an operator reconciles the target. */
public enum DeploymentOperationStatus {
    ACCEPTED, RUNNING, SUCCEEDED, FAILED, UNKNOWN
}
