package com.tapin.auth;

import com.tapin.shared.error.RateLimitedException;
import org.springframework.stereotype.Component;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;

/** Atomic sliding-window admission, independent of whether the target account exists. Single-instance MVP. */
@Component
public class AuthRequestLimiter {
    private final FailureCounter attempts = new FailureCounter(Duration.ofMinutes(15));
    private final Clock clock;

    public AuthRequestLimiter(Clock clock) { this.clock = clock; }

    public synchronized void acquire(String operation, String ip, String target) {
        var now = clock.instant();
        String addressKey = operation + ":ip:" + ip;
        String targetKey = operation + ":target:" + target.strip().toLowerCase(Locale.ROOT);
        long wait = Math.max(attempts.retryAfterSeconds(addressKey, 20, now),
                attempts.retryAfterSeconds(targetKey, 5, now));
        if (wait > 0) throw new RateLimitedException("Previše pokušaja. Pokušajte ponovno kasnije.", wait);
        attempts.record(addressKey, now);
        attempts.record(targetKey, now);
    }
}
