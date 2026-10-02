-- Sessions that outlive the 15-minute access token. Only SHA-256 hashes are stored: a leaked table gives no
-- session away. Deleting a user (the demo reset truncates users) takes their sessions along.
CREATE TABLE refresh_tokens (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    family_id   UUID        NOT NULL,
    token_hash  VARCHAR(64) NOT NULL UNIQUE,
    created_at  TIMESTAMPTZ NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL,
    used_at     TIMESTAMPTZ,
    revoked_at  TIMESTAMPTZ
);
CREATE INDEX refresh_tokens_family_idx ON refresh_tokens (family_id);
CREATE INDEX refresh_tokens_user_idx ON refresh_tokens (user_id);

-- One-time codes that carry a fresh login from the API's domain to the site's, where the cookie lives.
CREATE TABLE session_handoffs (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    code_hash   VARCHAR(64) NOT NULL UNIQUE,
    expires_at  TIMESTAMPTZ NOT NULL
);
CREATE INDEX session_handoffs_user_idx ON session_handoffs (user_id);
