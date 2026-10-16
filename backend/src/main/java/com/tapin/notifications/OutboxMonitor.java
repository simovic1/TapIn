package com.tapin.notifications;

import com.tapin.notifications.OutboxRepository.Backlog;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Alert on long-lasting e-mail problems (spec §13.1, BJJ-30). The queue is <b>degraded</b> while some PENDING message
 * is older than {@code tapin.outbox.alert.after}: the provider has been failing for that long, or no worker is
 * draining the queue. Entering that state logs an ERROR with the {@code OUTBOX_DEGRADED} marker text (repeated every
 * {@code repeat}), leaving it logs {@code OUTBOX_RECOVERED}; the gauge {@code tapin.outbox.degraded} is 1 meanwhile.
 * <p>
 * Deliberately <b>not</b> a health indicator: a DOWN health check would make the orchestrator restart or take out of
 * rotation an application that still records attendance perfectly well (§14: e-mail never blocks the records).
 */
@Component
class OutboxMonitor implements MeterBinder {

    private static final Logger log = LoggerFactory.getLogger(OutboxMonitor.class);

    /** Snapshot for the operator API and the gauge. */
    record State(boolean degraded, Instant degradedSince, long stuckPending, Instant oldestStuckCreatedAt,
                 String latestError, Instant checkedAt) {
    }

    private final OutboxRepository repository;
    private final OutboxAlertProperties properties;
    private final Clock clock;
    private volatile State state;
    private Instant lastAlertAt;

    OutboxMonitor(OutboxRepository repository, OutboxAlertProperties properties, Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
        this.state = new State(false, null, 0, null, null, null);
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("tapin.outbox.degraded", this, m -> m.state.degraded() ? 1 : 0)
                .description("1 while a PENDING e-mail is older than tapin.outbox.alert.after")
                .register(registry);
    }

    @Scheduled(fixedDelayString = "${tapin.outbox.alert.interval:1m}",
            initialDelayString = "${tapin.outbox.alert.interval:1m}")
    void scheduledCheck() {
        try {
            check();
        } catch (RuntimeException e) {
            log.error("Outbox monitor check failed", e);
        }
    }

    /** Re-evaluates the queue; synchronized because the scheduler and an operator request may both call it. */
    synchronized State check() {
        Instant now = clock.instant();
        Backlog backlog = repository.backlog(now.minus(properties.after()));
        boolean degraded = backlog.stuckPending() > 0;
        State previous = state;
        Instant since = degraded ? (previous.degraded() ? previous.degradedSince() : now) : null;
        state = new State(degraded, since, backlog.stuckPending(), backlog.oldestStuckCreatedAt(),
                backlog.latestError(), now);

        if (degraded && (!previous.degraded() || lastAlertAt == null
                || !now.isBefore(lastAlertAt.plus(properties.repeat())))) {
            lastAlertAt = now;
            log.error("OUTBOX_DEGRADED: {} e-mail(s) PENDING for more than {} (oldest queued {}), last error: {}. "
                            + "Records are saved normally; see docs/architecture/notifications.md#operativni-nadzor",
                    backlog.stuckPending(), properties.after(), backlog.oldestStuckCreatedAt(),
                    backlog.latestError() == null ? "none (is the outbox worker running?)" : backlog.latestError());
        } else if (!degraded && previous.degraded()) {
            lastAlertAt = null;
            log.warn("OUTBOX_RECOVERED: no e-mail PENDING for more than {} any more; degraded for {}",
                    properties.after(), Duration.between(previous.degradedSince(), now));
        }
        return state;
    }

    State state() {
        return state;
    }
}
