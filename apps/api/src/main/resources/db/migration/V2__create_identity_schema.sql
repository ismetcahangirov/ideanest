-- Users, credentials, sessions, refresh token families, and verification
-- tokens: everything needed to establish who someone is and to keep them
-- signed in without keeping a long-lived bearer token in play.
--
-- Reverse:
--   DROP TABLE IF EXISTS verification_tokens;
--   DROP TABLE IF EXISTS refresh_tokens;
--   DROP TABLE IF EXISTS sessions;
--   DROP TABLE IF EXISTS user_credentials;
--   DROP TABLE IF EXISTS users;
--   DROP FUNCTION IF EXISTS set_updated_at();

-- ---------------------------------------------------------------------------
-- updated_at, maintained by the database
-- ---------------------------------------------------------------------------

-- An application that forgets to set updated_at produces a row that lies about
-- when it last changed, and nothing fails at the time. A trigger cannot forget.
CREATE OR REPLACE FUNCTION set_updated_at() RETURNS trigger AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- ---------------------------------------------------------------------------
-- users
-- ---------------------------------------------------------------------------

CREATE TABLE users (
    id                uuid        PRIMARY KEY,
    -- citext, so that Person@Example.com and person@example.com are one
    -- account. Doing this with lower() in every query is one forgotten call
    -- away from two accounts for one person, and the second one cannot pledge
    -- with the first one's card.
    email             citext      NOT NULL,
    email_verified_at timestamptz,
    name              text        NOT NULL,
    -- Public profile URL. text rather than citext: the check below has to
    -- actually reject uppercase, and citext's operators fold case, which would
    -- make the pattern accept what it is written to refuse.
    slug              text        NOT NULL,
    avatar_url        text,
    bio               text,
    locale            text        NOT NULL DEFAULT 'az',
    currency          text        NOT NULL DEFAULT 'AZN',
    -- Soft delete. Financial records refer to the person who made a pledge, and
    -- a hard delete would either break that reference or rewrite history.
    deleted_at        timestamptz,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT users_email_shape CHECK (email ~ '^[^@[:space:]]+@[^@[:space:]]+\.[^@[:space:]]+$'),
    CONSTRAINT users_email_length CHECK (length(email::text) BETWEEN 3 AND 254),
    CONSTRAINT users_name_length CHECK (length(btrim(name)) BETWEEN 1 AND 80),
    -- Lowercase, digits, single hyphens, no leading or trailing hyphen.
    CONSTRAINT users_slug_shape CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    CONSTRAINT users_slug_length CHECK (length(slug) BETWEEN 3 AND 60),
    CONSTRAINT users_locale_supported CHECK (locale IN ('az', 'en', 'ru', 'tr')),
    CONSTRAINT users_currency_shape CHECK (currency ~ '^[A-Z]{3}$')
);

CREATE UNIQUE INDEX users_email_key ON users (email);
CREATE UNIQUE INDEX users_slug_key ON users (slug);

CREATE TRIGGER users_set_updated_at
    BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON TABLE users IS
    'A person. Deleting one is a soft delete; anonymisation is a separate step (#28).';

-- ---------------------------------------------------------------------------
-- user_credentials
-- ---------------------------------------------------------------------------

-- Separate from users for two reasons. A person who signed in with Google has
-- no password at all, so the column would be null for a growing share of rows
-- and say nothing about why. And a password hash that lives in the same table
-- as the profile is read into memory by every query that wanted a display name.
CREATE TABLE user_credentials (
    user_id             uuid        PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    -- The full encoded hash, including its parameters. Argon2 encodes the
    -- memory, time, and parallelism it was created with, which is what makes
    -- raising them later a rehash-on-next-sign-in rather than a lockout.
    password_hash       text        NOT NULL,
    algorithm           text        NOT NULL DEFAULT 'ARGON2ID',
    -- Sessions issued before a password change are revoked. That comparison
    -- needs a timestamp that changes only when the password does, which
    -- updated_at does not.
    password_changed_at timestamptz NOT NULL DEFAULT now(),
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT user_credentials_algorithm_supported CHECK (algorithm IN ('ARGON2ID')),
    CONSTRAINT user_credentials_hash_not_blank CHECK (length(btrim(password_hash)) > 0)
);

CREATE TRIGGER user_credentials_set_updated_at
    BEFORE UPDATE ON user_credentials
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON TABLE user_credentials IS
    'Password credentials. A user who signs in only through a provider has no row here.';

-- ---------------------------------------------------------------------------
-- sessions
-- ---------------------------------------------------------------------------

-- A session is the refresh token family. Rotation issues a new refresh token
-- into the same session; presenting a token that was already rotated means a
-- copy is in circulation, and the response to that is to revoke the session --
-- which is to say the family -- rather than the single token.
CREATE TABLE sessions (
    id             uuid        PRIMARY KEY,
    user_id        uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    -- Shown in the device list a user revokes from, so it has to be legible.
    device_label   text,
    user_agent     text,
    -- inet, not text: it validates, it normalises IPv6, and it makes "sessions
    -- from this network" a query rather than a string comparison.
    ip_address     inet,
    created_at     timestamptz NOT NULL DEFAULT now(),
    last_seen_at   timestamptz NOT NULL DEFAULT now(),
    expires_at     timestamptz NOT NULL,
    revoked_at     timestamptz,
    revoked_reason text,

    CONSTRAINT sessions_expiry_after_creation CHECK (expires_at > created_at),
    -- A revoked session must say why. "Someone was signed out and nobody
    -- recorded why" is the state that makes a token-theft incident
    -- unreconstructable afterwards.
    CONSTRAINT sessions_revocation_is_complete CHECK ((revoked_at IS NULL) = (revoked_reason IS NULL)),
    CONSTRAINT sessions_revoked_reason_known CHECK (
        revoked_reason IS NULL OR revoked_reason IN (
            'SIGNED_OUT',        -- the user signed out of this session
            'USER_REVOKED',      -- the user revoked it from the device list
            'TOKEN_REUSE',       -- a rotated refresh token was presented again
            'PASSWORD_CHANGED',  -- every other session dies with the password
            'ADMIN_ACTION'       -- support or trust and safety intervened
        )
    )
);

-- The device list, and every refresh: both ask for one user's live sessions.
CREATE INDEX sessions_live_by_user_idx ON sessions (user_id) WHERE revoked_at IS NULL;
-- Expiry sweeps read this and nothing else.
CREATE INDEX sessions_expires_at_idx ON sessions (expires_at);

COMMENT ON TABLE sessions IS
    'One sign-in on one device. Also the refresh token family: reuse revokes the session.';

-- ---------------------------------------------------------------------------
-- refresh_tokens
-- ---------------------------------------------------------------------------

CREATE TABLE refresh_tokens (
    id          uuid        PRIMARY KEY,
    session_id  uuid        NOT NULL REFERENCES sessions (id) ON DELETE CASCADE,
    -- SHA-256 of the opaque token, never the token. A database backup, a log
    -- line, or a support query then contains nothing anyone can sign in with.
    -- No salt and no work factor on purpose: the input is 256 bits of entropy
    -- we generated, so there is no dictionary to attack, and this hash is
    -- computed on every single refresh.
    token_hash  bytea       NOT NULL,
    issued_at   timestamptz NOT NULL DEFAULT now(),
    expires_at  timestamptz NOT NULL,
    -- Set when this token was exchanged for its successor. A used token is kept
    -- rather than deleted: deleting it makes a replayed token indistinguishable
    -- from one that never existed, and that difference is the theft signal.
    used_at     timestamptz,
    replaced_by uuid        REFERENCES refresh_tokens (id) ON DELETE SET NULL,

    CONSTRAINT refresh_tokens_hash_is_sha256 CHECK (octet_length(token_hash) = 32),
    CONSTRAINT refresh_tokens_expiry_after_issue CHECK (expires_at > issued_at),
    CONSTRAINT refresh_tokens_replacement_implies_use CHECK (replaced_by IS NULL OR used_at IS NOT NULL),
    CONSTRAINT refresh_tokens_not_self_replacing CHECK (replaced_by IS NULL OR replaced_by <> id)
);

-- Every refresh is a lookup by hash, and the uniqueness is what makes a
-- presented token identify exactly one row.
CREATE UNIQUE INDEX refresh_tokens_token_hash_key ON refresh_tokens (token_hash);
CREATE INDEX refresh_tokens_session_idx ON refresh_tokens (session_id);
CREATE INDEX refresh_tokens_expires_at_idx ON refresh_tokens (expires_at);

COMMENT ON TABLE refresh_tokens IS
    'Opaque refresh tokens, stored hashed. Rows survive rotation so that reuse is detectable.';

-- ---------------------------------------------------------------------------
-- verification_tokens
-- ---------------------------------------------------------------------------

CREATE TABLE verification_tokens (
    id          uuid        PRIMARY KEY,
    user_id     uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    purpose     text        NOT NULL,
    token_hash  bytea       NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    expires_at  timestamptz NOT NULL,
    -- Single use. The token is spent by setting this, not by deleting the row,
    -- so that a second attempt can be told apart from an unknown token.
    consumed_at timestamptz,

    CONSTRAINT verification_tokens_purpose_known CHECK (
        purpose IN ('EMAIL_VERIFICATION', 'PASSWORD_RESET')
    ),
    CONSTRAINT verification_tokens_hash_is_sha256 CHECK (octet_length(token_hash) = 32),
    CONSTRAINT verification_tokens_expiry_after_creation CHECK (expires_at > created_at)
);

CREATE UNIQUE INDEX verification_tokens_token_hash_key ON verification_tokens (token_hash);
-- "Does this person already have a live verification email out?" -- asked on
-- every resend, to avoid sending a second one that invalidates the first.
CREATE INDEX verification_tokens_outstanding_idx
    ON verification_tokens (user_id, purpose) WHERE consumed_at IS NULL;
CREATE INDEX verification_tokens_expires_at_idx ON verification_tokens (expires_at);

COMMENT ON TABLE verification_tokens IS
    'Single-use email verification and password reset tokens, stored hashed.';
