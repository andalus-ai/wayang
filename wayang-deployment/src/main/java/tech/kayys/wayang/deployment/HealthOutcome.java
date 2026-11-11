package tech.kayys.wayang.deployment;

/** Result supplied by an external, bounded health-check adapter. */
public enum HealthOutcome { HEALTHY, DEGRADED, UNHEALTHY, UNKNOWN }
