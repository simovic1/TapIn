package com.tapin.registrations;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Looks for due training reminders every minute (decision D-04). Idempotent, so several instances may run it.
 * Disabled with all jobs by {@code tapin.scheduling.enabled=false}; reminders as such by {@code tapin.reminders.enabled}.
 */
@Component
@ConditionalOnBooleanProperty(name = "tapin.scheduling.enabled", matchIfMissing = true)
class ReminderJob {

    private static final Logger log = LoggerFactory.getLogger(ReminderJob.class);

    private final TrainingReminderService reminders;

    ReminderJob(TrainingReminderService reminders) {
        this.reminders = reminders;
    }

    @Scheduled(fixedDelayString = "${tapin.reminders.interval:1m}", initialDelayString = "30s")
    void run() {
        try {
            reminders.enqueueDue();
        } catch (RuntimeException e) {
            log.error("Training reminder run failed", e);
        }
    }
}
