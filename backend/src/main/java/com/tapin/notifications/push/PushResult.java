package com.tapin.notifications.push;

/** Outcome of one push request to one subscription. Adapters never throw for delivery errors. */
public sealed interface PushResult {

    /** The push service accepted the message (201); it is not a delivery confirmation. */
    record Accepted(String providerMessageId) implements PushResult {
    }

    /** The subscription no longer exists (404/410) or is unusable: remove it, never retry. */
    record Gone(String reason) implements PushResult {
    }

    /** Temporary problem (network, 429, 5xx, rejected VAPID credentials); retry later. */
    record TransientFailure(String reason) implements PushResult {
    }

    /** The message itself is unacceptable (400, 413) and will not succeed on retry. */
    record PermanentFailure(String reason) implements PushResult {
    }
}
