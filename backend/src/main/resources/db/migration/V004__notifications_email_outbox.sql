-- V004 (notifications): transactional outbox for e-mail (spec §8.3).
--
-- A row is written in the SAME transaction as the business change that causes it (rollback removes both).
-- A background worker sends due rows outside that transaction and records the result here.
--
-- Status model:
--   PENDING  waiting for (re)delivery; due when next_attempt_at <= now and no live lease
--   SENT     accepted by the provider (sent_at, provider_message_id)
--   FAILED   permanent error or retries exhausted; can be re-queued manually (diagnostics in last_error)
-- A worker "claims" a PENDING row by setting locked_by/locked_until (lease) with FOR UPDATE SKIP LOCKED, so two
-- workers never process the same row at the same time. An expired lease (crashed worker) makes the row claimable.

CREATE TABLE email_outbox (
    id                        BIGINT GENERATED ALWAYS AS IDENTITY,
    event_type                VARCHAR(64)   NOT NULL,
    recipient_user_id         BIGINT        NOT NULL,
    -- Snapshot of the address at enqueue time (the notification goes where the user was when it happened).
    recipient_email           VARCHAR(320)  NOT NULL,
    -- Optional training session reference; the FK is added by the scheduling migration that creates the table.
    session_id                BIGINT,
    payload                   JSONB         NOT NULL DEFAULT '{}'::jsonb,
    -- For messages carrying secrets (invitation / password-reset links): payload is wiped once sent or failed.
    redact_payload_after_send BOOLEAN       NOT NULL DEFAULT FALSE,
    deduplication_key         VARCHAR(255)  NOT NULL,
    status                    VARCHAR(16)   NOT NULL DEFAULT 'PENDING',
    attempt_count             INTEGER       NOT NULL DEFAULT 0,
    next_attempt_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    last_attempt_at           TIMESTAMPTZ,
    last_error                VARCHAR(2000),
    locked_by                 VARCHAR(100),
    locked_until              TIMESTAMPTZ,
    provider_message_id       VARCHAR(255),
    sent_at                   TIMESTAMPTZ,
    created_at                TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at                TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT pk_email_outbox PRIMARY KEY (id),
    CONSTRAINT fk_email_outbox_recipient FOREIGN KEY (recipient_user_id) REFERENCES users (id),
    -- The same business event can never produce a second outbox row (idempotent enqueue).
    CONSTRAINT uk_email_outbox_deduplication_key UNIQUE (deduplication_key),
    CONSTRAINT ck_email_outbox_status CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    CONSTRAINT ck_email_outbox_event_type CHECK (event_type IN (
        'INVITATION', 'REGISTRATION_CONFIRMED', 'REGISTRATION_CANCELLED', 'TRAINING_REMINDER',
        'TRAINING_CANCELLED', 'TRAINING_RESCHEDULED', 'ATTENDANCE_RECORDED', 'PASSWORD_RESET')),
    CONSTRAINT ck_email_outbox_attempt_count CHECK (attempt_count >= 0),
    CONSTRAINT ck_email_outbox_sent_has_timestamp CHECK (status <> 'SENT' OR sent_at IS NOT NULL),
    CONSTRAINT ck_email_outbox_lease CHECK ((locked_by IS NULL) = (locked_until IS NULL))
);

-- Worker poll: only PENDING rows, ordered by due time.
CREATE INDEX ix_email_outbox_due ON email_outbox (next_attempt_at, id) WHERE status = 'PENDING';
-- Diagnostics / monitoring (FAILED queue, oldest pending).
CREATE INDEX ix_email_outbox_status_created ON email_outbox (status, created_at);
CREATE INDEX ix_email_outbox_recipient ON email_outbox (recipient_user_id);
CREATE INDEX ix_email_outbox_session ON email_outbox (session_id) WHERE session_id IS NOT NULL;

COMMENT ON TABLE email_outbox IS 'notifications: transactional outbox for e-mail; written by business modules, drained by OutboxWorker';
COMMENT ON COLUMN email_outbox.attempt_count IS 'Incremented when a worker claims the row (a crash mid-send still counts as an attempt)';
