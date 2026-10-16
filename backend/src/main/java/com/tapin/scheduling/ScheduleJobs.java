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
 * materialisation is idempotent per (rule, original date). Disabled together with all jobs by
 * {@code tapin.scheduling.enabled=false} (tests drive the services directly).
 */
@Component
@ConditionalOnBooleanProperty(name = "tapin.scheduling.enabled", matchIfMissing = true)
class ScheduleJobs {

    private static final Logger log = LoggerFactory.getLogger(ScheduleJobs.class);

    private final SessionMaterializer materializer;

    ScheduleJobs(SessionMaterializer materializer) {
        this.materializer = materializer;
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
}
