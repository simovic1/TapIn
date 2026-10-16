package com.tapin.notifications;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;

/**
 * Queue health gauges (spec §13.1 "outbox reda"): {@code tapin.outbox.messages{status=...}} and
 * {@code tapin.outbox.oldest.pending.age} (seconds). Alert on growing FAILED or an old PENDING message.
 */
@Component
class OutboxMetrics implements MeterBinder {

    private final OutboxRepository repository;
    private final Clock clock;

    OutboxMetrics(OutboxRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        for (OutboxStatus status : OutboxStatus.values()) {
            Gauge.builder("tapin.outbox.messages", repository, r -> r.countByStatus().get(status))
                    .description("E-mail outbox rows by status")
                    .tag("status", status.name())
                    .register(registry);
        }
        Gauge.builder("tapin.outbox.oldest.pending.age", repository,
                        r -> r.oldestPendingCreatedAt()
                                .map(created -> (double) Duration.between(created, clock.instant()).toSeconds())
                                .orElse(0.0))
                .description("Age of the oldest PENDING outbox row")
                .baseUnit("seconds")
                .register(registry);
    }
}
