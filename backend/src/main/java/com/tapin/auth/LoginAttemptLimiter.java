package com.tapin.auth;

import com.tapin.shared.error.RateLimitedException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;

/** Failed-login throttling per e-mail and per IP (spec §14 "rate limit za prijavu"). */
@Component
class LoginAttemptLimiter {

    private final LoginAttemptProperties properties;
    private final Clock clock;
    private final FailureCounter failures;

    LoginAttemptLimiter(LoginAttemptProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        this.failures = new FailureCounter(properties.window());
    }

    /** @throws RateLimitedException if the e-mail or the IP has too many recent failures */
    void checkAllowed(String email, String ip) {
        Instant now = clock.instant();
        long retryAfter = Math.max(
                failures.retryAfterSeconds(emailKey(email), properties.maxFailuresPerEmail(), now),
                failures.retryAfterSeconds(ipKey(ip), properties.maxFailuresPerIp(), now));
        if (retryAfter > 0) {
            throw new RateLimitedException("Previše neuspjelih prijava. Pokušajte ponovno kasnije.", retryAfter);
        }
    }

    void recordFailure(String email, String ip) {
        Instant now = clock.instant();
        failures.record(emailKey(email), now);
        failures.record(ipKey(ip), now);
    }

    /** A successful login clears the e-mail's counter (the IP counter keeps protecting other accounts). */
    void recordSuccess(String email) {
        failures.clear(emailKey(email));
    }

    private static String emailKey(String email) {
        return "email:" + email.strip().toLowerCase(Locale.ROOT);
    }

    private static String ipKey(String ip) {
        return "ip:" + ip;
    }
}
