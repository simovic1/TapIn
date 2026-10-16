package com.tapin.notifications.email;

/** Result of one delivery attempt, classified so the worker can decide between SENT, retry and FAILED. */
public sealed interface SendResult {

    /** The provider accepted the message. */
    record Accepted(String providerMessageId) implements SendResult {
    }

    /**
     * Temporary problem (timeout, connection refused, 4xx, throttling): retry with backoff.
     *
     * @param ambiguous true when the provider may have accepted the message although we saw an error
     *                  (e.g. a timeout after the message data was transmitted). Retrying such a message can deliver
     *                  it twice; that is accepted by design (at-least-once), see docs/architecture/notifications.md.
     */
    record TransientFailure(String reason, boolean ambiguous) implements SendResult {
    }

    /** The message can never be delivered as is (invalid address, rejected content): no retry. */
    record PermanentFailure(String reason) implements SendResult {
    }
}
