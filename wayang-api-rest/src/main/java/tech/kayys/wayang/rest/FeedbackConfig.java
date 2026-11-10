package tech.kayys.wayang.rest;

import java.time.Clock;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import tech.kayys.wayang.api.feedback.FeedbackService;
import tech.kayys.wayang.api.feedback.FeedbackStore;
import tech.kayys.wayang.store.jdbc.JdbcFeedbackStore;

/** Feedback lives in the same store kind as the rest ({@code wayang.store}): memory is bounded and lost on restart. */
@ApplicationScoped
public class FeedbackConfig {

    @ConfigProperty(name = "wayang.store", defaultValue = "memory")
    String storeKind;
    @ConfigProperty(name = "wayang.feedback.memory-entries-per-tenant", defaultValue = "2000")
    int memoryEntries;

    @Inject
    WayangBeans beans;
    @Inject
    Clock clock;

    private volatile FeedbackService service;

    public synchronized FeedbackService service() {
        if (service == null) {
            FeedbackStore store = "jdbc".equals(storeKind) ? new JdbcFeedbackStore(beans.jdbc()) : new FeedbackStore.InMemory(memoryEntries);
            service = new FeedbackService(store, clock, Limits.FEEDBACK);
        }
        return service;
    }
}
