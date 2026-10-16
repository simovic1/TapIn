package com.tapin.shared.error;

/** 429 with a {@code Retry-After} header (seconds). */
public class RateLimitedException extends ApiException {

    private final long retryAfterSeconds;

    public RateLimitedException(String detail, long retryAfterSeconds) {
        super(ErrorCode.RATE_LIMITED, detail);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
