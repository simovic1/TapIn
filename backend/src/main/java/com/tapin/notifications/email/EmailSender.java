package com.tapin.notifications.email;

/**
 * Provider adapter. Implementations must not throw for delivery problems; they classify them into a
 * {@link SendResult}. Implementations for providers with request idempotency (e.g. an {@code Idempotency-Key}
 * header) must pass {@link EmailMessage#idempotencyKey()} so a retry after an ambiguous response is deduplicated
 * by the provider.
 */
public interface EmailSender {

    SendResult send(EmailMessage message);

    /** Short provider name for logs and diagnostics. */
    String name();
}
