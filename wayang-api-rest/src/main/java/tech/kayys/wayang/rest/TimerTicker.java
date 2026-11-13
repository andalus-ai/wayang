package tech.kayys.wayang.rest;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.quarkus.scheduler.Scheduled;
import tech.kayys.wayang.api.run.RunEngine;

/**
 * Background duties: fire due timers every few seconds and, with the Gamelan
 * engine, poll run progress to feed WebSocket events. Several nodes may run
 * this against one database only with a durable registry (atomic take).
 */
@ApplicationScoped
public class TimerTicker {

    @Inject
    RunEngine engine;
    @Inject
    tech.kayys.wayang.api.trigger.TriggerService triggers;
    @Inject
    tech.kayys.wayang.api.run.CollectSweepService collects;
    @Inject
    FeedbackConfig feedback;
    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "wayang.feedback.retention-days", defaultValue = "0")
    int feedbackRetentionDays;

    @Scheduled(every = "{wayang.timers.interval:5s}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void tick() {
        engine.fireDueTimers();
    }

    @Scheduled(every = "{wayang.events.poll-interval:2s}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void poll() {
        engine.poll();
    }

    /** Polls due triggers (chat, mailbox). Single node only: see {@code TriggerService}. */
    @Scheduled(every = "{wayang.triggers.interval:5s}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    @io.smallrye.common.annotation.Blocking
    void triggers() {
        triggers.tick();
    }

    /** Closes {@code collect} groups past their deadline and starts their {@code sweepTo} process. */
    @Scheduled(every = "{wayang.collect.sweep-interval:15s}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    @io.smallrye.common.annotation.Blocking
    void collects() {
        collects.sweep();
    }

    /** Drops feedback older than {@code wayang.feedback.retention-days} (0 = keep everything, the default). */
    @Scheduled(every = "1h", delayed = "5m", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    @io.smallrye.common.annotation.Blocking
    void feedbackRetention() {
        if (feedbackRetentionDays > 0) {
            feedback.service().applyRetention(feedbackRetentionDays);
        }
    }
}
