-- V008 (registrations): a member's registration for a session and its change history (spec §5, §6.1, §10).
--
-- A registration is REGISTERED or CANCELLED; it is never deleted (re-registering reuses the row), so there is at
-- most one registration per member and session (AC-03). Whether the member actually came is attendance, a separate
-- module and table: registration never implies presence.

CREATE TABLE training_registrations (
    id            BIGINT GENERATED ALWAYS AS IDENTITY,
    session_id    BIGINT      NOT NULL,
    club_id       BIGINT      NOT NULL,
    membership_id BIGINT      NOT NULL,
    status        VARCHAR(32) NOT NULL,
    -- Time of the latest (re-)registration and of the latest cancellation.
    registered_at TIMESTAMPTZ NOT NULL,
    cancelled_at  TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    version       BIGINT      NOT NULL DEFAULT 0,

    CONSTRAINT pk_training_registrations PRIMARY KEY (id),
    -- Same-club integrity (§10.2): the session and the membership must belong to the same club.
    CONSTRAINT fk_training_registrations_session FOREIGN KEY (session_id, club_id)
        REFERENCES training_sessions (id, club_id),
    CONSTRAINT fk_training_registrations_membership FOREIGN KEY (membership_id, club_id)
        REFERENCES club_memberships (id, club_id),
    CONSTRAINT uk_training_registrations_session_membership UNIQUE (session_id, membership_id),
    CONSTRAINT ck_training_registrations_status CHECK (status IN ('REGISTERED', 'CANCELLED')),
    CONSTRAINT ck_training_registrations_cancelled CHECK ((status = 'CANCELLED') = (cancelled_at IS NOT NULL))
);

CREATE INDEX ix_training_registrations_session_status ON training_registrations (session_id, status);
CREATE INDEX ix_training_registrations_membership ON training_registrations (membership_id);

-- Append-only history of registration changes (forensic clarity, spec §7). Its id is the business-event id in the
-- notification deduplication key (REGISTRATION_CONFIRMED:registration-event-12), so a retried request that changes
-- nothing writes neither an event nor an e-mail.
CREATE TABLE registration_events (
    id              BIGINT GENERATED ALWAYS AS IDENTITY,
    registration_id BIGINT      NOT NULL,
    event_type      VARCHAR(32) NOT NULL,
    actor_user_id   BIGINT,
    source          VARCHAR(32) NOT NULL,
    occurred_at     TIMESTAMPTZ NOT NULL,

    CONSTRAINT pk_registration_events PRIMARY KEY (id),
    CONSTRAINT fk_registration_events_registration FOREIGN KEY (registration_id) REFERENCES training_registrations (id),
    CONSTRAINT fk_registration_events_actor FOREIGN KEY (actor_user_id) REFERENCES users (id),
    CONSTRAINT ck_registration_events_type CHECK (event_type IN ('REGISTERED', 'CANCELLED', 'RE_REGISTERED')),
    CONSTRAINT ck_registration_events_source CHECK (source IN ('SELF', 'TRAINER'))
);

CREATE INDEX ix_registration_events_registration ON registration_events (registration_id, occurred_at);

COMMENT ON TABLE training_registrations IS 'registrations: one row per member and session; REGISTERED or CANCELLED, never deleted';
COMMENT ON TABLE registration_events IS 'registrations: append-only history of registration changes';
