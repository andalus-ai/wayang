package tech.kayys.wayang.control.dto;

/**
 * Enterprise Integration Pattern types
 */
public enum EIPPatternType {
    // Messaging Patterns
    MESSAGE_CHANNEL,
    MESSAGE_ROUTER,
    MESSAGE_TRANSLATOR,
    MESSAGE_FILTER,

    // Routing Patterns
    CONTENT_BASED_ROUTER,
    RECIPIENT_LIST,
    SPLITTER,
    AGGREGATOR,

    // Transformation Patterns
    ENVELOPE_WRAPPER,
    CONTENT_ENRICHER,
    CONTENT_FILTER,
    NORMALIZER,

    // Endpoint Patterns
    MESSAGING_GATEWAY,
    POLLING_CONSUMER,
    EVENT_DRIVEN_CONSUMER,
    SERVICE_ACTIVATOR
}
