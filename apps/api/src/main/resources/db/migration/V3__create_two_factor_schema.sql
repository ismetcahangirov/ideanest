-- Two-factor authentication: the TOTP secret, the recovery codes that survive a
-- lost phone, and the short-lived challenge that stands between a correct
-- password and a session.
--
-- Reverse:
--   ALTER TABLE sessions DROP COLUMN two_factor_at;
--   DROP TABLE IF EXISTS two_factor_challenges;
--   DROP TABLE IF EXISTS two_factor_recovery_codes;
--   DROP TABLE IF EXISTS user_two_factor;

-- ---------------------------------------------------------------------------
-- user_two_factor
-- ---------------------------------------------------------------------------

-- One row per user who has started enrolling. Enrolment and enablement are
-- deliberately two states of one row rather than two tables: a secret that has
-- been generated but never proved is not a second factor, and confirmed_at is
-- the only thing that says which of the two this is.
--
-- The secret is stored as raw bytes and cannot be hashed the way a token is.
-- Verifying a code means recomputing HMAC over the secret, so the server needs
-- it back. Encryption at rest with a managed key is the control that belongs
-- here and there is no key management in the platform yet; until there is, the
-- protection is the same as for every other row in this database.
CREATE TABLE user_two_factor (
    user_id        uuid        PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    -- 160 bits, the size RFC 4226 §4 recommends for an HMAC-SHA1 key.
    secret         bytea       NOT NULL,
    algorithm      text        NOT NULL DEFAULT 'TOTP_SHA1',
    -- Null until a current code has been entered. Two-factor is off for this
    -- user until it is set: enrolling without confirming must never be able to
    -- lock somebody out of their own account.
    confirmed_at   timestamptz,
    -- The last time step whose code was accepted. A code is refused unless its
    -- step is strictly greater, which is what makes a code single-use inside
    -- its own window -- otherwise anybody who reads it over a shoulder, or out
    -- of a proxy log, has thirty more seconds to use it.
    last_used_step bigint,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT user_two_factor_algorithm_known CHECK (algorithm IN ('TOTP_SHA1')),
    CONSTRAINT user_two_factor_secret_length CHECK (octet_length(secret) = 20),
    -- A step recorded against a secret nobody ever confirmed would mean a code
    -- was accepted while two-factor was off.
    CONSTRAINT user_two_factor_step_implies_confirmation CHECK (
        last_used_step IS NULL OR confirmed_at IS NOT NULL
    )
);

CREATE TRIGGER user_two_factor_set_updated_at
    BEFORE UPDATE ON user_two_factor
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON TABLE user_two_factor IS
    'TOTP secrets. A row with confirmed_at NULL is an enrolment in progress, not a second factor.';

-- ---------------------------------------------------------------------------
-- two_factor_recovery_codes
-- ---------------------------------------------------------------------------

-- What a user has left when the phone is gone. Stored as SHA-256, like a
-- refresh token and for the same reason: the input is 100 bits we generated,
-- so there is no dictionary and no rainbow table, and an unsalted digest with
-- no work factor is exactly right for that. Argon2 here would be indefensible
-- in the other direction -- it is verified on an endpoint reachable with a
-- stolen challenge, so a memory-hard hash would let a caller spend 19 MiB per
-- guess of ours.
CREATE TABLE two_factor_recovery_codes (
    id         uuid        PRIMARY KEY,
    user_id    uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    code_hash  bytea       NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    -- Single use, spent by setting this rather than by deleting the row, so
    -- that "you have already used this one" can be told apart from "that is
    -- not a code", and so that the user's remaining count stays honest.
    used_at    timestamptz,

    CONSTRAINT two_factor_recovery_codes_hash_is_sha256 CHECK (octet_length(code_hash) = 32)
);

-- Uniqueness is what makes a presented code identify exactly one row. Without
-- it a collision -- however unlikely -- would return either of two users.
CREATE UNIQUE INDEX two_factor_recovery_codes_hash_key ON two_factor_recovery_codes (code_hash);
-- "How many does this person have left", asked every time the list is shown.
CREATE INDEX two_factor_recovery_codes_unused_idx
    ON two_factor_recovery_codes (user_id) WHERE used_at IS NULL;

COMMENT ON TABLE two_factor_recovery_codes IS
    'Single-use recovery codes, stored as SHA-256. Shown once, at confirmation.';

-- ---------------------------------------------------------------------------
-- two_factor_challenges
-- ---------------------------------------------------------------------------

-- The state between the two halves of a sign-in. A correct password with
-- two-factor on produces one of these and no session; the second call spends it
-- together with a code.
--
-- It is a row rather than a signed token because it has to be revocable and
-- single-use, and a stateless token is neither: the whole point is that the
-- thing cannot be replayed, and nothing that is only signed can promise that.
-- It carries the device description from the first call so that the session
-- created by the second one describes the device that actually signed in.
CREATE TABLE two_factor_challenges (
    id             uuid        PRIMARY KEY,
    user_id        uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    -- SHA-256 of a 256-bit opaque value, never the value. A challenge is a
    -- credential for the length of its life, and is stored like one.
    challenge_hash bytea       NOT NULL,
    device_label   text,
    user_agent     text,
    ip_address     inet,
    created_at     timestamptz NOT NULL,
    -- Minutes, not hours. It exists to carry one person across one form.
    expires_at     timestamptz NOT NULL,
    consumed_at    timestamptz,

    CONSTRAINT two_factor_challenges_hash_is_sha256 CHECK (octet_length(challenge_hash) = 32),
    CONSTRAINT two_factor_challenges_expiry_after_creation CHECK (expires_at > created_at)
);

CREATE UNIQUE INDEX two_factor_challenges_hash_key ON two_factor_challenges (challenge_hash);
-- Expiry sweeps read this and nothing else.
CREATE INDEX two_factor_challenges_expires_at_idx ON two_factor_challenges (expires_at);

COMMENT ON TABLE two_factor_challenges IS
    'Short-lived single-use proof that a password was accepted. Not a session and not a token.';

-- ---------------------------------------------------------------------------
-- sessions.two_factor_at
-- ---------------------------------------------------------------------------

-- Whether this session proved a second factor, and when. Payout actions require
-- it, and a session that was started with a password alone must not satisfy
-- that requirement merely because the account has two-factor switched on. The
-- claim in the access token is minted from this column.
ALTER TABLE sessions ADD COLUMN two_factor_at timestamptz;

COMMENT ON COLUMN sessions.two_factor_at IS
    'When this session proved a second factor. NULL means a password alone.';
