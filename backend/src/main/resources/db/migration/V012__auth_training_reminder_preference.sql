-- V012 (auth): a person's choice to receive the training reminder (spec §8.2, decision D-04).
-- On by default; the member switches it off on "Moj profil". Other notifications are not affected.

ALTER TABLE users ADD COLUMN training_reminders_enabled BOOLEAN NOT NULL DEFAULT TRUE;

COMMENT ON COLUMN users.training_reminders_enabled IS 'auth: receive the reminder 3 h before registered trainings';
