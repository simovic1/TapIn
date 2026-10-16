-- V013 (notifications): optional expiry of an outbox message (decision D-04).
-- A message still undelivered at expires_at is not sent any more (e.g. a training reminder after the training
-- started); the processor marks it FAILED as stale instead.

ALTER TABLE email_outbox ADD COLUMN expires_at TIMESTAMPTZ;

COMMENT ON COLUMN email_outbox.expires_at IS 'notifications: do not deliver after this instant (stale), NULL = no expiry';
