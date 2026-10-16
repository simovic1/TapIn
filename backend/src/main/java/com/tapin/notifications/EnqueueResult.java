package com.tapin.notifications;

/** Outcome of {@link NotificationOutbox#enqueue}; maps 1:1 to the API's {@code notificationStatus}. */
public enum EnqueueResult {
    /** A new outbox row was written in the caller's transaction (not a delivery confirmation). */
    QUEUED,
    /** A row with the same deduplication key already exists; nothing was written. */
    DUPLICATE;

    /** Value for the API field {@code notificationStatus} ({@code QUEUED} / {@code NOT_REQUIRED}). */
    public String notificationStatus() {
        return this == QUEUED ? "QUEUED" : "NOT_REQUIRED";
    }
}
