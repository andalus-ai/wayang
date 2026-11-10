package tech.kayys.wayang.spike;

/** Result of an idempotent append attempt. */
public enum PressureEventAppendResult {
    APPENDED,
    DUPLICATE
}
