package com.tapin.notifications;

import java.time.Duration;
import java.time.Instant;
import java.util.function.DoubleSupplier;

/**
 * Exponential backoff with +/-10 % jitter: initial, 2x, 4x, ... capped at {@code maxBackoff}.
 * With the defaults (1 min, cap 1 h, 8 attempts) a message is retried for roughly 3 hours before it is FAILED.
 */
class RetryPolicy {

    private static final double JITTER = 0.10;

    private final Duration initialBackoff;
    private final Duration maxBackoff;
    private final int maxAttempts;
    private final DoubleSupplier random;

    RetryPolicy(OutboxProperties properties, DoubleSupplier random) {
        this.initialBackoff = properties.initialBackoff();
        this.maxBackoff = properties.maxBackoff();
        this.maxAttempts = properties.maxAttempts();
        this.random = random;
    }

    /** @param attemptCount attempts made so far (including the one that just failed) */
    boolean isExhausted(int attemptCount) {
        return attemptCount >= maxAttempts;
    }

    Instant nextAttemptAt(int attemptCount, Instant now) {
        return now.plus(backoff(attemptCount));
    }

    Duration backoff(int attemptCount) {
        int exponent = Math.min(Math.max(attemptCount - 1, 0), 30);
        long base = Math.min(initialBackoff.toMillis() * (1L << exponent), maxBackoff.toMillis());
        double factor = 1 + JITTER * (2 * random.getAsDouble() - 1);
        return Duration.ofMillis(Math.min(Math.round(base * factor), maxBackoff.toMillis()));
    }
}
