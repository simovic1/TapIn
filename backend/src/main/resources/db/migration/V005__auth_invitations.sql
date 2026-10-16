-- V005 (auth): invitations (spec §3.2).
--
-- Only the SHA-256 hash of the one-time token is stored. An invitation is open while accepted_at and revoked_at are
-- NULL and expires_at is in the future. Re-sending revokes the open invitation and issues a new one, so a user has at
-- most one open invitation (uk_invitations_one_open_per_user).

CREATE TABLE invitations (
    id          BIGINT GENERATED ALWAYS AS IDENTITY,
    club_id     BIGINT       NOT NULL,
    user_id     BIGINT       NOT NULL,
    -- Address the invitation was sent to (snapshot for diagnostics).
    email       VARCHAR(320) NOT NULL,
    token_hash  CHAR(64)     NOT NULL,
    inviter_id  BIGINT,
    expires_at  TIMESTAMPTZ  NOT NULL,
    accepted_at TIMESTAMPTZ,
    revoked_at  TIMESTAMPTZ,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT pk_invitations PRIMARY KEY (id),
    CONSTRAINT fk_invitations_club FOREIGN KEY (club_id) REFERENCES clubs (id),
    CONSTRAINT fk_invitations_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_invitations_inviter FOREIGN KEY (inviter_id) REFERENCES users (id),
    CONSTRAINT uk_invitations_token_hash UNIQUE (token_hash),
    CONSTRAINT ck_invitations_token_hash_hex CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_invitations_expiry CHECK (expires_at > created_at),
    CONSTRAINT ck_invitations_single_outcome CHECK (accepted_at IS NULL OR revoked_at IS NULL)
);

CREATE UNIQUE INDEX uk_invitations_one_open_per_user ON invitations (user_id)
    WHERE accepted_at IS NULL AND revoked_at IS NULL;

COMMENT ON TABLE invitations IS 'auth: one-time invitations; token stored as SHA-256 hash only';
