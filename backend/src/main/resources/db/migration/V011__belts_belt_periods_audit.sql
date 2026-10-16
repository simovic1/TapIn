-- V011 (belts): belt history of a member and its append-only audit (spec §3.3, §10). No stripes (AC-31).
--
-- A period starts on effective_from_date (inclusive, club-local date) and ends where the next period of the same
-- membership starts (exclusive), so periods can never overlap and a training never counts twice. awarded_on is the
-- real award date shown to users; it may be unknown (NULL) for old belts and is never invented.
-- AFTER_TRAINING: effective_from_date = awarded_on + 1 day; BEFORE_TRAINING: effective_from_date = awarded_on.

CREATE TABLE member_belt_periods (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY,
    membership_id       BIGINT       NOT NULL,
    belt                VARCHAR(16)  NOT NULL,
    awarded_on          DATE,
    effective_from_date DATE         NOT NULL,
    awarded_by_user_id  BIGINT,
    note                VARCHAR(500),
    created_by_user_id  BIGINT       NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version             BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT pk_member_belt_periods PRIMARY KEY (id),
    CONSTRAINT fk_member_belt_periods_membership FOREIGN KEY (membership_id) REFERENCES club_memberships (id),
    CONSTRAINT fk_member_belt_periods_awarded_by FOREIGN KEY (awarded_by_user_id) REFERENCES users (id),
    CONSTRAINT fk_member_belt_periods_created_by FOREIGN KEY (created_by_user_id) REFERENCES users (id),
    -- One period per start date and member (spec §10): the second promotion with the same start is a 409.
    CONSTRAINT uk_member_belt_periods_membership_effective UNIQUE (membership_id, effective_from_date),
    CONSTRAINT ck_member_belt_periods_belt CHECK (belt IN ('WHITE', 'BLUE', 'PURPLE', 'BROWN', 'BLACK')),
    -- The count starts on the award day (before training) or the day after (after training).
    CONSTRAINT ck_member_belt_periods_effective CHECK (
        awarded_on IS NULL OR effective_from_date = awarded_on OR effective_from_date = awarded_on + 1)
);

CREATE TABLE belt_period_audit_logs (
    id             BIGINT GENERATED ALWAYS AS IDENTITY,
    belt_period_id BIGINT       NOT NULL,
    membership_id  BIGINT       NOT NULL,
    actor_user_id  BIGINT       NOT NULL,
    action         VARCHAR(16)  NOT NULL,
    old_values     JSONB,
    new_values     JSONB        NOT NULL,
    reason         VARCHAR(500),
    occurred_at    TIMESTAMPTZ  NOT NULL,

    CONSTRAINT pk_belt_period_audit_logs PRIMARY KEY (id),
    CONSTRAINT fk_belt_period_audit_logs_period FOREIGN KEY (belt_period_id) REFERENCES member_belt_periods (id),
    CONSTRAINT fk_belt_period_audit_logs_membership FOREIGN KEY (membership_id) REFERENCES club_memberships (id),
    CONSTRAINT fk_belt_period_audit_logs_actor FOREIGN KEY (actor_user_id) REFERENCES users (id),
    CONSTRAINT ck_belt_period_audit_logs_action CHECK (action IN ('CREATED', 'CORRECTED'))
);

CREATE INDEX ix_belt_period_audit_logs_membership_occurred ON belt_period_audit_logs (membership_id, occurred_at);

-- Append-only (spec §3.3 "bez fizičkog brisanja povijesti"): no update or delete of audit rows.
CREATE FUNCTION belt_period_audit_logs_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'belt_period_audit_logs is append-only (% not allowed)', TG_OP;
END;
$$;

CREATE TRIGGER tg_belt_period_audit_logs_append_only
    BEFORE UPDATE OR DELETE ON belt_period_audit_logs
    FOR EACH ROW EXECUTE FUNCTION belt_period_audit_logs_append_only();

COMMENT ON TABLE member_belt_periods IS 'belts: belt periods per membership; current belt = latest period; counts are computed, never stored';
COMMENT ON TABLE belt_period_audit_logs IS 'belts: append-only audit of belt period creation and correction';
