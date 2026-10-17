package com.tapin.notifications;

/** Delivery channel of an outbox row. Must match {@code ck_email_outbox_channel}. */
public enum OutboxChannel {
    /** E-mail to {@code recipient_email}. */
    EMAIL,
    /** Web push to every push subscription of the recipient user (optional channel, e.g. training reminders). */
    PUSH
}
