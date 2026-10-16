-- V001 (shared): schema baseline.
--
-- Intentionally contains no tables. It anchors the Flyway history so every later migration follows the agreed
-- order and naming (docs/architecture/database-migrations.md):
--
--   V{NNN}__{module}_{description}.sql   e.g. V002__auth_users.sql
--
-- Planned order for the MVP:
--   V002__auth_users
--   V003__members_clubs_memberships_roles
--   V004__auth_invitations_password_resets
--   V005__scheduling_recurring_schedules_sessions
--   V006__registrations_training_registrations
--   V007__attendance_attendances_audit
--   V008__belts_belt_periods_audit
--   V009__notifications_email_outbox
--   R__reporting_*  (repeatable read-only views)

COMMENT ON SCHEMA public IS 'TapIn - evidencija BJJ treninga';
