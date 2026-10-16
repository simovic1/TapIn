-- V007 (scheduling): recurring schedule rules, training sessions, session coaches and the session change log
-- (spec §4, §10).
--
-- Times: a rule stores LOCAL wall-clock time (weekday + start/end TIME) in the club time zone; a session stores UTC
-- instants. Materialisation converts (occurrence_date, start_time) in Europe/Zagreb to an instant, so a Tuesday stays
-- 18:30 local on both sides of a DST change (AC-19).

CREATE TABLE recurring_schedules (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY,
    club_id            BIGINT      NOT NULL,
    weekday            VARCHAR(16) NOT NULL,
    start_time         TIME        NOT NULL,
    end_time           TIME        NOT NULL,
    valid_from         DATE        NOT NULL,
    -- Inclusive; NULL = open-ended.
    valid_to           DATE,
    enabled            BOOLEAN     NOT NULL DEFAULT TRUE,
    created_by_user_id BIGINT,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    version            BIGINT      NOT NULL DEFAULT 0,

    CONSTRAINT pk_recurring_schedules PRIMARY KEY (id),
    CONSTRAINT fk_recurring_schedules_club FOREIGN KEY (club_id) REFERENCES clubs (id),
    CONSTRAINT fk_recurring_schedules_created_by FOREIGN KEY (created_by_user_id) REFERENCES users (id),
    CONSTRAINT ck_recurring_schedules_weekday CHECK (weekday IN
        ('MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY')),
    -- A session never spans midnight in the MVP (one evening group).
    CONSTRAINT ck_recurring_schedules_time_order CHECK (end_time > start_time),
    CONSTRAINT ck_recurring_schedules_validity CHECK (valid_to IS NULL OR valid_to >= valid_from)
);

CREATE INDEX ix_recurring_schedules_club_weekday ON recurring_schedules (club_id, weekday);

-- Base schedule (spec §4.1): Tuesday and Thursday 18:30-20:00 Europe/Zagreb. Sessions are only materialised from
-- "now" forward, so the past valid_from does not create historical sessions.
INSERT INTO recurring_schedules (club_id, weekday, start_time, end_time, valid_from)
SELECT id, 'TUESDAY', TIME '18:30', TIME '20:00', DATE '2026-01-01' FROM clubs;
INSERT INTO recurring_schedules (club_id, weekday, start_time, end_time, valid_from)
SELECT id, 'THURSDAY', TIME '18:30', TIME '20:00', DATE '2026-01-01' FROM clubs;

CREATE TABLE training_sessions (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY,
    club_id              BIGINT       NOT NULL,
    -- Rule and ORIGINAL date of a materialised occurrence; both NULL for an extra/historical session.
    schedule_id          BIGINT,
    occurrence_date      DATE,
    title                VARCHAR(150),
    note                 VARCHAR(500),
    start_at             TIMESTAMPTZ  NOT NULL,
    end_at               TIMESTAMPTZ  NOT NULL,
    status               VARCHAR(32)  NOT NULL DEFAULT 'SCHEDULED',
    -- TRUE once the time of a rule occurrence was changed individually: later rule edits no longer touch it.
    time_overridden      BOOLEAN      NOT NULL DEFAULT FALSE,
    cancellation_reason  VARCHAR(500),
    cancelled_at         TIMESTAMPTZ,
    cancelled_by_user_id BIGINT,
    -- NULL for sessions materialised from a rule.
    created_by_user_id   BIGINT,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version              BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT pk_training_sessions PRIMARY KEY (id),
    CONSTRAINT fk_training_sessions_club FOREIGN KEY (club_id) REFERENCES clubs (id),
    CONSTRAINT fk_training_sessions_schedule FOREIGN KEY (schedule_id) REFERENCES recurring_schedules (id),
    CONSTRAINT fk_training_sessions_created_by FOREIGN KEY (created_by_user_id) REFERENCES users (id),
    CONSTRAINT fk_training_sessions_cancelled_by FOREIGN KEY (cancelled_by_user_id) REFERENCES users (id),
    -- Target for composite FKs (session_id, club_id) from registrations/attendance (same-club integrity, §10.2).
    CONSTRAINT uk_training_sessions_id_club UNIQUE (id, club_id),
    -- One session per rule and original date, ever (spec §4.2, AC-12): a moved or cancelled occurrence keeps its
    -- row, so repeated or concurrent materialisation can never create it again.
    CONSTRAINT uk_training_sessions_schedule_occurrence UNIQUE (schedule_id, occurrence_date),
    CONSTRAINT ck_training_sessions_occurrence_pair CHECK ((schedule_id IS NULL) = (occurrence_date IS NULL)),
    CONSTRAINT ck_training_sessions_status CHECK (status IN ('SCHEDULED', 'CANCELLED', 'HELD')),
    CONSTRAINT ck_training_sessions_time_order CHECK (end_at > start_at),
    CONSTRAINT ck_training_sessions_cancelled CHECK ((status = 'CANCELLED') = (cancelled_at IS NOT NULL)),
    -- No overlapping ACTIVE sessions in the club (spec §4.2). Enforced by the database, so two concurrent requests
    -- cannot both succeed. Single club in the MVP; add club_id (btree_gist) when a second club is introduced.
    CONSTRAINT ex_training_sessions_no_overlap EXCLUDE USING gist (tstzrange(start_at, end_at, '[)') WITH &&)
        WHERE (status <> 'CANCELLED')
);

CREATE INDEX ix_training_sessions_start_at_status ON training_sessions (start_at, status);
CREATE INDEX ix_training_sessions_status_end_at ON training_sessions (status, end_at) WHERE status = 'SCHEDULED';

CREATE TABLE session_coaches (
    session_id    BIGINT NOT NULL,
    membership_id BIGINT NOT NULL,

    CONSTRAINT pk_session_coaches PRIMARY KEY (session_id, membership_id),
    CONSTRAINT fk_session_coaches_session FOREIGN KEY (session_id) REFERENCES training_sessions (id) ON DELETE CASCADE,
    CONSTRAINT fk_session_coaches_membership FOREIGN KEY (membership_id) REFERENCES club_memberships (id)
);

CREATE INDEX ix_session_coaches_membership ON session_coaches (membership_id);

-- Append-only change log of sessions (created, moved, edited, cancelled, held). Its id is the stable business-event
-- id used in notification deduplication keys (TRAINING_CANCELLED:session-12:event-40:membership-7).
CREATE TABLE training_session_events (
    id            BIGINT GENERATED ALWAYS AS IDENTITY,
    session_id    BIGINT      NOT NULL,
    event_type    VARCHAR(32) NOT NULL,
    -- NULL for system actions (materialisation, automatic HELD transition).
    actor_user_id BIGINT,
    old_values    JSONB,
    new_values    JSONB,
    occurred_at   TIMESTAMPTZ NOT NULL,

    CONSTRAINT pk_training_session_events PRIMARY KEY (id),
    CONSTRAINT fk_training_session_events_session FOREIGN KEY (session_id) REFERENCES training_sessions (id),
    CONSTRAINT fk_training_session_events_actor FOREIGN KEY (actor_user_id) REFERENCES users (id),
    CONSTRAINT ck_training_session_events_type CHECK (event_type IN
        ('CREATED', 'RESCHEDULED', 'UPDATED', 'CANCELLED', 'HELD'))
);

CREATE INDEX ix_training_session_events_session ON training_session_events (session_id, occurred_at);

-- email_outbox.session_id was created in V004 without a FK because this table did not exist yet.
ALTER TABLE email_outbox
    ADD CONSTRAINT fk_email_outbox_session FOREIGN KEY (session_id) REFERENCES training_sessions (id);

COMMENT ON TABLE recurring_schedules IS 'scheduling: weekly rules in club-local time; edits affect only the future';
COMMENT ON TABLE training_sessions IS 'scheduling: concrete sessions (materialised from rules or created manually); never deleted';
COMMENT ON TABLE session_coaches IS 'scheduling: assigned coaches (information only, not an access restriction)';
COMMENT ON TABLE training_session_events IS 'scheduling: append-only change log of sessions';
