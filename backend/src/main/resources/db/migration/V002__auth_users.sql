-- V002 (auth): user accounts / identity.
--
-- A user is a person with login credentials. Club-specific data (membership, roles, belts) lives in the
-- members/belts modules and references users.id.

CREATE TABLE users (
    id             BIGINT GENERATED ALWAYS AS IDENTITY,
    email          VARCHAR(320) NOT NULL,
    first_name     VARCHAR(100) NOT NULL,
    last_name      VARCHAR(100) NOT NULL,
    phone          VARCHAR(32),
    -- NULL until the invitation is accepted. Only a password hash is ever stored (delegating encoder, e.g. {bcrypt}).
    password_hash  VARCHAR(255),
    account_status VARCHAR(32)  NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version        BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT pk_users PRIMARY KEY (id),
    CONSTRAINT ck_users_account_status CHECK (account_status IN ('INVITED', 'ACTIVE', 'DISABLED')),
    CONSTRAINT ck_users_email_format CHECK (email = btrim(email) AND email LIKE '_%@_%'),
    CONSTRAINT ck_users_names_not_blank CHECK (btrim(first_name) <> '' AND btrim(last_name) <> ''),
    CONSTRAINT ck_users_active_has_password CHECK (account_status <> 'ACTIVE' OR password_hash IS NOT NULL)
);

-- E-mail is unique regardless of letter case (spec §3.1); lookups use lower(email) and hit this index.
CREATE UNIQUE INDEX uk_users_email_lower ON users (lower(email));

COMMENT ON TABLE users IS 'auth: user accounts (identity and credentials)';
COMMENT ON COLUMN users.account_status IS 'INVITED until the invitation is accepted; not the same as membership status';
