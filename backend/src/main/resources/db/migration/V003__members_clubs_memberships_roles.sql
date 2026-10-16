-- V003 (members): club, memberships, roles and membership status history.

CREATE TABLE clubs (
    id         BIGINT GENERATED ALWAYS AS IDENTITY,
    name       VARCHAR(150) NOT NULL,
    time_zone  VARCHAR(64)  NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT pk_clubs PRIMARY KEY (id),
    CONSTRAINT ck_clubs_name_not_blank CHECK (btrim(name) <> ''),
    CONSTRAINT ck_clubs_time_zone_not_blank CHECK (btrim(time_zone) <> '')
);

-- MVP runs exactly one club (spec §10.1). This guard rejects a second row; drop it when multi-club is introduced.
CREATE UNIQUE INDEX uk_clubs_single_club ON clubs ((true));

INSERT INTO clubs (name, time_zone) VALUES ('BJJ klub', 'Europe/Zagreb');

CREATE TABLE club_memberships (
    id         BIGINT GENERATED ALWAYS AS IDENTITY,
    club_id    BIGINT      NOT NULL,
    user_id    BIGINT      NOT NULL,
    joined_on  DATE        NOT NULL,
    status     VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version    BIGINT      NOT NULL DEFAULT 0,

    CONSTRAINT pk_club_memberships PRIMARY KEY (id),
    CONSTRAINT fk_club_memberships_club FOREIGN KEY (club_id) REFERENCES clubs (id),
    CONSTRAINT fk_club_memberships_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT uk_club_memberships_club_user UNIQUE (club_id, user_id),
    -- Target for composite FKs (club_id, membership_id) from later modules, so attendance/registrations can only
    -- reference members of the same club as the training session (spec §10.2).
    CONSTRAINT uk_club_memberships_id_club UNIQUE (id, club_id),
    CONSTRAINT ck_club_memberships_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

CREATE INDEX ix_club_memberships_user_id ON club_memberships (user_id);

CREATE TABLE membership_roles (
    membership_id BIGINT      NOT NULL,
    role          VARCHAR(32) NOT NULL,

    CONSTRAINT pk_membership_roles PRIMARY KEY (membership_id, role),
    CONSTRAINT fk_membership_roles_membership FOREIGN KEY (membership_id)
        REFERENCES club_memberships (id) ON DELETE CASCADE,
    CONSTRAINT ck_membership_roles_role CHECK (role IN ('MEMBER', 'TRAINER', 'ADMIN'))
);

CREATE INDEX ix_membership_roles_role ON membership_roles (role);

-- Needed for exact historical attendance percentages when a membership is (de)activated (spec §9.1, §10).
CREATE TABLE membership_status_events (
    id            BIGINT GENERATED ALWAYS AS IDENTITY,
    membership_id BIGINT      NOT NULL,
    old_status    VARCHAR(32),
    new_status    VARCHAR(32) NOT NULL,
    effective_at  TIMESTAMPTZ NOT NULL,
    actor_user_id BIGINT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT pk_membership_status_events PRIMARY KEY (id),
    CONSTRAINT fk_membership_status_events_membership FOREIGN KEY (membership_id) REFERENCES club_memberships (id),
    CONSTRAINT fk_membership_status_events_actor FOREIGN KEY (actor_user_id) REFERENCES users (id),
    CONSTRAINT ck_membership_status_events_old_status CHECK (old_status IS NULL OR old_status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT ck_membership_status_events_new_status CHECK (new_status IN ('ACTIVE', 'INACTIVE'))
);

CREATE INDEX ix_membership_status_events_membership_effective
    ON membership_status_events (membership_id, effective_at);

COMMENT ON TABLE clubs IS 'members: the club (MVP: exactly one)';
COMMENT ON TABLE club_memberships IS 'members: a user''s membership in a club; belt is NOT stored here (derived from member_belt_periods)';
COMMENT ON TABLE membership_roles IS 'members: additive roles per membership';
COMMENT ON TABLE membership_status_events IS 'members: append-only history of membership status changes';
