-- V014 (notifications): web push as an optional second channel for notifications (training reminders).
--
-- push_subscriptions: one row per browser/device that a member allowed to receive push notifications
-- (PushSubscription from the browser's Push API). The endpoint identifies the browser installation; when another
-- person signs in on the same device and switches push on, the row moves to that person (no reminders for the
-- previous one). A push service answering 404/410 removes the row.
--
-- email_outbox gets a channel: push messages use the same transactional outbox (written in the business
-- transaction, delivered by the worker after commit), so a push failure never blocks a business change.
-- The table keeps its name; channel = 'EMAIL' for every existing row.

CREATE TABLE push_subscriptions (
    id           BIGINT GENERATED ALWAYS AS IDENTITY,
    user_id      BIGINT        NOT NULL,
    endpoint     VARCHAR(2048) NOT NULL,
    -- Browser's P-256 public key (uncompressed point, base64url) and 16-byte auth secret (base64url), RFC 8291.
    p256dh       VARCHAR(128)  NOT NULL,
    auth         VARCHAR(64)   NOT NULL,
    user_agent   VARCHAR(255),
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT pk_push_subscriptions PRIMARY KEY (id),
    CONSTRAINT fk_push_subscriptions_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT uk_push_subscriptions_endpoint UNIQUE (endpoint)
);

CREATE INDEX ix_push_subscriptions_user ON push_subscriptions (user_id);

COMMENT ON TABLE push_subscriptions IS 'notifications: browser push subscriptions (Web Push, VAPID); written only by PushSubscriptions';

ALTER TABLE email_outbox ADD COLUMN channel VARCHAR(16) NOT NULL DEFAULT 'EMAIL';
ALTER TABLE email_outbox ADD CONSTRAINT ck_email_outbox_channel CHECK (channel IN ('EMAIL', 'PUSH'));

COMMENT ON COLUMN email_outbox.channel IS 'notifications: EMAIL (recipient_email) or PUSH (all push_subscriptions of recipient_user_id)';
