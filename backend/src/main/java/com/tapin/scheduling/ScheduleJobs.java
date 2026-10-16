package com.tapin.scheduling;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * In-process background jobs of the scheduling module (spec §13.1). Safe to run on several instances at once:
 * materialisation is idempotent per (rule, original date) and the HELD transition updates each row once.
 * Disabled together with all jobs by {@code tapin.scheduling.enabled=false} (tests drive the services directly).
 */
@Component
@ConditionalOnBooleanProperty(name = "tapin.scheduling.enabled", matchIfMissing = true)
class ScheduleJobs {

    private static final Logger log = LoggerFactory.getLogger(ScheduleJobs.class);

    private final SessionMaterializer materializer;
    private final TrainingService trainings;

    ScheduleJobs(SessionMaterializer materializer, TrainingService trainings) {
        this.materializer = materializer;
        this.trainings = trainings;
    }

    @EventListener(ApplicationReadyEvent.class)
    void materializeOnStartup() {
        materialize();
    }

    @Scheduled(fixedDelayString = "${tapin.schedule.materialize-interval:6h}",
            initialDelayString = "${tapin.schedule.materialize-interval:6h}")
    void materialize() {
        try {
            materializer.materialize();
        } catch (RuntimeException e) {
            log.error("Session materialisation failed", e);
        }
    }

    /** SCHEDULED -> HELD once a session has ended (spec §4.3). */
    @Scheduled(fixedDelayString = "${tapin.schedule.held-transition-interval:1m}")
    void markEndedAsHeld() {
        try {
            int held = trainings.markEndedAsHeld();
            if (held > 0) {
                log.info("Marked {} ended training session(s) as HELD", held);
            }
        } catch (RuntimeException e) {
            log.error("HELD transition failed", e);
        }
    }
}
