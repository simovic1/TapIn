-- V009 (attendance): recorded attendance and its append-only audit (spec §6, §7, §10).
--
-- API semantics: attendance is PRESENT, ABSENT or UNMARKED (= nothing decided). A trainer's "back to UNMARKED" keeps
-- the row with status UNMARKED instead of deleting it, so its version keeps increasing: a stale request still
-- carrying an old version can never succeed after a reset (a deleted-and-reinserted row would start at version 0
-- again). No row at all means UNMARKED with version 0. Statistics count only PRESENT.

CREATE TABLE training_attendances (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY,
    session_id         BIGINT      NOT NULL,
    club_id            BIGINT      NOT NULL,
    membership_id      BIGINT      NOT NULL,
    status             VARCHAR(32) NOT NULL,
    updated_by_user_id BIGINT      NOT NULL,
    updated_at         TIMESTAMPTZ NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- 1 after the first write; "no row" is version 0 in the API.
    version            BIGINT      NOT NULL DEFAULT 1,

    CONSTRAINT pk_training_attendances PRIMARY KEY (id),
    -- Attendance only for members of the session's club (§10.2).
    CONSTRAINT fk_training_attendances_session FOREIGN KEY (session_id, club_id)
        REFERENCES training_sessions (id, club_id),
    CONSTRAINT fk_training_attendances_membership FOREIGN KEY (membership_id, club_id)
        REFERENCES club_memberships (id, club_id),
    CONSTRAINT fk_training_attendances_updated_by FOREIGN KEY (updated_by_user_id) REFERENCES users (id),
    -- One attendance per member and session; also decides two concurrent first inserts.
    CONSTRAINT uk_training_attendances_session_membership UNIQUE (session_id, membership_id),
    CONSTRAINT ck_training_attendances_status CHECK (status IN ('PRESENT', 'ABSENT', 'UNMARKED')),
    CONSTRAINT ck_training_attendances_version CHECK (version >= 1)
);

CREATE INDEX ix_training_attendances_membership_status ON training_attendances (membership_id, status);

CREATE TABLE attendance_audit_logs (
    id            BIGINT GENERATED ALWAYS AS IDENTITY,
    session_id    BIGINT       NOT NULL,
    membership_id BIGINT       NOT NULL,
    actor_user_id BIGINT       NOT NULL,
    old_status    VARCHAR(32)  NOT NULL,
    new_status    VARCHAR(32)  NOT NULL,
    action_source VARCHAR(32)  NOT NULL,
    note          VARCHAR(500),
    occurred_at   TIMESTAMPTZ  NOT NULL,

    CONSTRAINT pk_attendance_audit_logs PRIMARY KEY (id),
    CONSTRAINT fk_attendance_audit_logs_session FOREIGN KEY (session_id) REFERENCES training_sessions (id),
    CONSTRAINT fk_attendance_audit_logs_membership FOREIGN KEY (membership_id) REFERENCES club_memberships (id),
    CONSTRAINT fk_attendance_audit_logs_actor FOREIGN KEY (actor_user_id) REFERENCES users (id),
    CONSTRAINT ck_attendance_audit_logs_old_status CHECK (old_status IN ('PRESENT', 'ABSENT', 'UNMARKED')),
    CONSTRAINT ck_attendance_audit_logs_new_status CHECK (new_status IN ('PRESENT', 'ABSENT', 'UNMARKED')),
    -- Only real changes are audited.
    CONSTRAINT ck_attendance_audit_logs_changed CHECK (old_status <> new_status),
    CONSTRAINT ck_attendance_audit_logs_source CHECK (action_source IN ('SINGLE', 'BULK', 'HISTORICAL_IMPORT', 'CORRECTION'))
);

CREATE INDEX ix_attendance_audit_logs_membership_occurred ON attendance_audit_logs (membership_id, occurred_at);
CREATE INDEX ix_attendance_audit_logs_session_occurred ON attendance_audit_logs (session_id, occurred_at);

-- Append-only (spec §7): rows can be inserted, never changed or deleted by the application.
CREATE FUNCTION attendance_audit_logs_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'attendance_audit_logs is append-only (% not allowed)', TG_OP USING ERRCODE = 'insufficient_privilege';
END;
$$;

CREATE TRIGGER tg_attendance_audit_logs_append_only
    BEFORE UPDATE OR DELETE ON attendance_audit_logs
    FOR EACH ROW EXECUTE FUNCTION attendance_audit_logs_append_only();

COMMENT ON TABLE training_attendances IS 'attendance: PRESENT/ABSENT/UNMARKED per member and session; written only by AttendanceService';
COMMENT ON TABLE attendance_audit_logs IS 'attendance: append-only audit of every real attendance change';
