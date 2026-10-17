-- Missing/expired subscriptions are an expected inbox-only outcome, not a provider failure.
ALTER TABLE email_outbox DROP CONSTRAINT ck_email_outbox_status;
ALTER TABLE email_outbox ADD CONSTRAINT ck_email_outbox_status
    CHECK (status IN ('PENDING', 'SENT', 'FAILED', 'SKIPPED'));

-- Repair only the known no-target failures, preserving inbox content and read state.
-- Do not retry old notifications or claim that a provider accepted them.
UPDATE email_outbox
SET status = 'SKIPPED', last_error = NULL, updated_at = now()
WHERE channel = 'PUSH' AND status = 'FAILED'
  AND (last_error = 'No push subscription (push switched off on every device)'
       OR last_error ~ '^All [0-9]+ push subscription\(s\) expired and were removed$');
