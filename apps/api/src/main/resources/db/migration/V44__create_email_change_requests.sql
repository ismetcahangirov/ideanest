-- §4.1's A-12 (#277): an address change that has been asked for and not yet
-- proven.
--
-- ---------------------------------------------------------------------------
-- Reverse:
--   DROP TABLE email_change_requests;
--   -- Every outstanding change becomes one that never happened. That is the
--   -- correct loss: an unproven address is not an account's address, and the
--   -- person is still reachable at the one on `users`. No contract half is
--   -- dropped -- nothing else references this table.
-- ---------------------------------------------------------------------------
--
-- ---------------------------------------------------------------------------
-- WHY THE NEW ADDRESS IS NOT WRITTEN TO `users` AND MARKED UNVERIFIED
-- ---------------------------------------------------------------------------
--
-- The obvious alternative is to set `users.email` immediately and clear
-- `email_verified_at`. It is wrong in a way that only shows up when somebody
-- mistypes: the account's address would already have moved to a mailbox nobody
-- can read, sign-in is by address, and the reset link that would fix it goes to
-- the new address too. One typo and the account is gone.
--
-- So the change is held here until the new address answers, and `users.email`
-- moves in a single statement at that moment. Until then the account is
-- entirely unaffected: the old address still signs in, still receives, still
-- resets.
--
-- ---------------------------------------------------------------------------
-- WHY IT IS NOT A `verification_tokens` ROW
-- ---------------------------------------------------------------------------
--
-- `verification_tokens` is (user, purpose, token hash, expiry) and has nowhere
-- to put the address being proven. A third purpose plus a nullable payload
-- column on that table would make every EMAIL_VERIFICATION and PASSWORD_RESET
-- row carry a column that is null for it, and would put an email address in a
-- table §17.4's erasure sweeps by hash rather than by content.
--
-- The token itself is stored the same way and for the same reason: SHA-256 of
-- 256 bits from `SecureTokens`, so a leaked backup of this table cannot be
-- turned back into a link.
--
-- ---------------------------------------------------------------------------
-- ONE OUTSTANDING REQUEST PER ACCOUNT IS *NOT* ENFORCED HERE
-- ---------------------------------------------------------------------------
--
-- A partial unique index on `(user_id) WHERE consumed_at IS NULL` was the first
-- draft. It is refused for the reason the reset flow gives: somebody who asks
-- twice because the first message has not arrived would get a constraint
-- violation rather than a second message. The application consumes the
-- outstanding rows before inserting a new one -- the same statement shape
-- `VerificationTokenRepository.consumeOutstanding` uses -- so at most one row is
-- live, and a race that produced two leaves the older one spendable for its
-- remaining minutes rather than failing somebody's request.

CREATE TABLE email_change_requests
(
    id         uuid PRIMARY KEY,
    user_id    uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,

    -- citext, so that Person@Example.com and person@example.com are one address
    -- here exactly as they are on `users`. Without it the uniqueness check below
    -- and the one on `users.email` would disagree about what a duplicate is.
    new_email  citext      NOT NULL,

    -- SHA-256 of the value that was emailed. bytea rather than text: it is 32
    -- bytes and never read by a human.
    token_hash bytea       NOT NULL,

    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,

    -- Set when the link is spent, and never cleared. A row is kept rather than
    -- deleted so that a second click can be told "already used" instead of
    -- "not a link", which is the distinction `verification_tokens` exists to
    -- keep and the one a support conversation turns on.
    consumed_at timestamptz,

    CONSTRAINT email_change_requests_expires_after_creation CHECK (expires_at > created_at)
);

-- The lookup the confirmation endpoint makes, and the only one. Unique because
-- two rows sharing a token hash would mean the generator collided on 256 bits or
-- somebody inserted a hash they did not generate; both are conditions to refuse
-- rather than to resolve by picking a row.
CREATE UNIQUE INDEX email_change_requests_token_hash_key ON email_change_requests (token_hash);

-- "What is outstanding for this account", asked before a new request is written
-- and by nothing else. Partial, because a consumed row is never looked up this
-- way and almost every row is consumed or expired.
CREATE INDEX email_change_requests_outstanding_idx
    ON email_change_requests (user_id)
    WHERE consumed_at IS NULL;

COMMENT ON TABLE email_change_requests IS
    'A-12 (#277). An address change held until the new address proves itself; users.email does not move until then.';
COMMENT ON COLUMN email_change_requests.new_email IS
    'The address being proven. Not yet the account''s address, and deliberately not written to users.email until confirmation.';
COMMENT ON COLUMN email_change_requests.token_hash IS
    'SHA-256 of the emailed token. The value itself exists only in the message.';
