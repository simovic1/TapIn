package com.tapin.auth;

import com.tapin.shared.error.RateLimitedException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.HashMap;
import java.util.Map;

/**
 * In-memory sliding-window limiter for failed logins (spec §14 "rate limit za prijavu"). Single instance is enough
 * for the MVP (one application instance); a shared store would be needed for several instances.
 */
@Component
class LoginAttemptLimiter {

    private final LoginAttemptProperties properties;
    private final Clock clock;
    private final Map<String, Deque<Instant>> failures = new HashMap<>();

    LoginAttemptLimiter(LoginAttemptProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /** @throws RateLimitedException if the e-mail or the IP has too many recent failures */
    synchronized void checkAllowed(String email, String ip) {
        Instant now = clock.instant();
        long retryAfter = Math.max(
                retryAfter(emailKey(email), properties.maxFailuresPerEmail(), now),
                retryAfter(ipKey(ip), properties.maxFailuresPerIp(), now));
        if (retryAfter > 0) {
            throw new RateLimitedException("Previše neuspjelih prijava. Pokušajte ponovno kasnije.", retryAfter);
        }
    }

    synchronized void recordFailure(String email, String ip) {
        Instant now = clock.instant();
        for (String key : new String[]{emailKey(email), ipKey(ip)}) {
            Deque<Instant> times = failures.computeIfAbsent(key, k -> new ArrayDeque<>());
            prune(times, now);
            times.addLast(now);
        }
    }

    /** A successful login clears the e-mail's counter (the IP counter keeps protecting other accounts). */
    synchronized void recordSuccess(String email) {
        failures.remove(emailKey(email));
    }

    private long retryAfter(String key, int max, Instant now) {
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
        Instant unblockAt = times.peekFirst().plus(properties.window());
        return Math.max(1, Duration.between(now, unblockAt).toSeconds());
    }

    private void prune(Deque<Instant> times, Instant now) {
        Instant cutoff = now.minus(properties.window());
        while (!times.isEmpty() && times.peekFirst().isBefore(cutoff)) {
            times.pollFirst();
        }
    }

    private static String emailKey(String email) {
        return "email:" + email.strip().toLowerCase(Locale.ROOT);
    }

    private static String ipKey(String ip) {
        return "ip:" + ip;
    }
}
