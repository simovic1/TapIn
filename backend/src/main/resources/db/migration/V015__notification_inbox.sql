-- The inbox persists regardless of browser permission or delivery outcome.
ALTER TABLE email_outbox ADD COLUMN read_at TIMESTAMPTZ;
CREATE INDEX ix_outbox_inbox ON email_outbox (recipient_user_id, id DESC) WHERE channel = 'PUSH';
-- Stop pending member e-mails on upgrade. Account access links remain e-mail.
UPDATE email_outbox SET channel = 'PUSH'
WHERE channel = 'EMAIL' AND status = 'PENDING'
  AND event_type NOT IN ('INVITATION', 'PASSWORD_RESET')
  AND NOT redact_payload_after_send;
