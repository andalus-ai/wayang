package tech.kayys.wayang.deployment;

/** Deployment lifecycle states. Package installation and application activation are distinct. */
public enum DeploymentState {
    INSTALLED, ACTIVATING, ACTIVE, DEGRADED, FAILED, ROLLING_BACK, ROLLED_BACK, DEACTIVATING, DEACTIVATED
}
