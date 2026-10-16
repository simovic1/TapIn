package com.tapin.notifications;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Background job inside the application (no separate service, spec §13.1). Several application instances may run
 * it at the same time: claiming uses row locks and leases, so each row is processed by one worker at a time.
 * Disable with {@code tapin.outbox.worker.enabled=false}.
 */
@Component
@ConditionalOnBooleanProperty(name = "tapin.outbox.worker.enabled", matchIfMissing = true)
class OutboxWorker {

    private static final Logger log = LoggerFactory.getLogger(OutboxWorker.class);
    /** Upper bound of batches per poll, so one poll cannot monopolise the scheduler thread. */
    private static final int MAX_BATCHES_PER_POLL = 50;

    private final OutboxProcessor processor;

    OutboxWorker(OutboxProcessor processor) {
        this.processor = processor;
        log.info("Outbox worker {} started", processor.workerId());
    }

    @Scheduled(fixedDelayString = "${tapin.outbox.poll-interval:10s}",
            initialDelayString = "${tapin.outbox.poll-interval:10s}")
    void poll() {
        try {
            // A full batch means more may be due: keep draining instead of waiting a whole interval per batch.
            for (int i = 0; i < MAX_BATCHES_PER_POLL; i++) {
                if (processor.processDue().claimed() < processor.batchSize()) {
                    return;
                }
            }
        } catch (RuntimeException e) {
            // Database unavailable etc.: never let the scheduler thread die; the next poll retries.
            log.error("Outbox poll failed", e);
        }
    }
}
