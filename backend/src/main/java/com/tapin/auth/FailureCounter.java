package com.tapin.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Sliding-window failure counter per key (in memory, thread-safe). Shared by the login and invitation-token
 * limiters. A single application instance is enough for the MVP; several instances would need a shared store.
 */
final class FailureCounter {

    private final Duration window;
    private final Map<String, Deque<Instant>> failures = new HashMap<>();

    FailureCounter(Duration window) {
        this.window = window;
    }

    /** @return seconds until the key is allowed again, or 0 if it has fewer than {@code max} recent failures */
    synchronized long retryAfterSeconds(String key, int max, Instant now) {
        Deque<Instant> times = failures.get(key);
        if (times == null) {
            return 0;
        }
        prune(times, now);
        if (times.isEmpty()) {
            failures.remove(key);
            return 0;
        }
        if (times.size() < max) {
            return 0;
        }
        return Math.max(1, Duration.between(now, times.peekFirst().plus(window)).toSeconds());
    }

    synchronized void record(String key, Instant now) {
        Deque<Instant> times = failures.computeIfAbsent(key, k -> new ArrayDeque<>());
        prune(times, now);
        times.addLast(now);
    }

    synchronized void clear(String key) {
        failures.remove(key);
    }

    private void prune(Deque<Instant> times, Instant now) {
        Instant cutoff = now.minus(window);
        while (!times.isEmpty() && times.peekFirst().isBefore(cutoff)) {
            times.pollFirst();
        }
    }
}
