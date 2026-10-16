ALTER TABLE users ADD COLUMN credentials_version BIGINT NOT NULL DEFAULT 0;

CREATE TABLE password_reset_tokens (
    id BIGINT GENERATED ALWAYS AS IDENTITY,
    user_id BIGINT NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    CONSTRAINT pk_password_reset_tokens PRIMARY KEY (id),
    CONSTRAINT fk_password_reset_tokens_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT uk_password_reset_tokens_hash UNIQUE (token_hash),
    CONSTRAINT ck_password_reset_tokens_hash CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_password_reset_tokens_expiry CHECK (expires_at > created_at),
    CONSTRAINT ck_password_reset_tokens_outcome CHECK (consumed_at IS NULL OR revoked_at IS NULL)
);
CREATE INDEX ix_password_reset_tokens_user_id ON password_reset_tokens(user_id);
CREATE UNIQUE INDEX uk_password_reset_tokens_one_open ON password_reset_tokens(user_id)
    WHERE consumed_at IS NULL AND revoked_at IS NULL;
