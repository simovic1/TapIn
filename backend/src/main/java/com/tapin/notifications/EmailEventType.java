package com.tapin.notifications;

/** E-mail notification types (spec §8.1). Must match {@code ck_email_outbox_event_type}. */
public enum EmailEventType {
    INVITATION,
    REGISTRATION_CONFIRMED,
    REGISTRATION_CANCELLED,
    TRAINING_REMINDER,
    TRAINING_CANCELLED,
    TRAINING_RESCHEDULED,
    ATTENDANCE_RECORDED,
    PASSWORD_RESET
}
