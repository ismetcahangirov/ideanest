-- Deferred account deletion: a request, a grace period, and then anonymisation.
--
-- §17.4 says "on deletion: 30-day delay, then anonymisation. Financial records
-- retained for the statutory period". Three columns are what that sentence
-- needs: when the person asked, when the anonymisation falls due, and whether
-- it has already happened.
--
-- Purely additive, and every column is nullable with no default, so the release
-- running before this one keeps working against the same table.
--
-- Reverse:
--   DROP INDEX IF EXISTS users_anonymisation_due_idx;
--   ALTER TABLE users
--       DROP CONSTRAINT IF EXISTS users_deletion_request_is_complete,
--       DROP CONSTRAINT IF EXISTS users_deletion_is_scheduled_after_request,
--       DROP CONSTRAINT IF EXISTS users_anonymisation_follows_a_request,
--       DROP CONSTRAINT IF EXISTS users_anonymisation_implies_deletion,
--       DROP COLUMN IF EXISTS deletion_requested_at,
--       DROP COLUMN IF EXISTS deletion_scheduled_at,
--       DROP COLUMN IF EXISTS anonymised_at;
--   -- Reversing loses the pending deletion requests, which are instructions
--   -- from users that we would then silently stop carrying out. Already
--   -- anonymised rows are NOT recoverable by this or any other statement: the
--   -- values were overwritten in place and are gone. That is the point of them.

ALTER TABLE users
    -- When the person asked. Kept separately from the due date so that a change
    -- to the grace period is visible as a change, rather than rewriting when
    -- somebody asked to leave.
    ADD COLUMN deletion_requested_at timestamptz,
    -- When anonymisation falls due. Stored rather than computed from the
    -- request date plus a configured period: the promise made to the user is a
    -- date, and re-deriving it later would move that date whenever the setting
    -- changed.
    ADD COLUMN deletion_scheduled_at timestamptz,
    -- Set once, by the job, and never cleared. This is what makes the job
    -- idempotent and safe on more than one instance: anonymisation is claimed
    -- with `WHERE anonymised_at IS NULL`, so a second run finds nothing.
    ADD COLUMN anonymised_at        timestamptz;

ALTER TABLE users
    -- Half a request is not a state anything knows how to act on: a row with a
    -- due date and no request would be anonymised by the job with nobody having
    -- asked for it.
    ADD CONSTRAINT users_deletion_request_is_complete
        CHECK ((deletion_requested_at IS NULL) = (deletion_scheduled_at IS NULL)),
    -- A grace period that has already elapsed when it is granted is not a grace
    -- period.
    ADD CONSTRAINT users_deletion_is_scheduled_after_request
        CHECK (deletion_scheduled_at IS NULL OR deletion_scheduled_at > deletion_requested_at),
    ADD CONSTRAINT users_anonymisation_follows_a_request
        CHECK (anonymised_at IS NULL OR deletion_requested_at IS NOT NULL),
    -- An anonymised row has no owner left to sign in as it, so it must also be
    -- soft-deleted. Without this an account could be anonymised and still be
    -- returned by every finder, which is worse than either state alone.
    ADD CONSTRAINT users_anonymisation_implies_deletion
        CHECK (anonymised_at IS NULL OR deleted_at IS NOT NULL);

-- The only query the job runs: which requests have come due and are not done.
-- Partial, because the rows that interest it are a vanishing fraction of the
-- table and a full index on a mostly-null column would be paid for on every
-- write to `users`.
CREATE INDEX users_anonymisation_due_idx
    ON users (deletion_scheduled_at)
    WHERE anonymised_at IS NULL AND deletion_scheduled_at IS NOT NULL;

COMMENT ON COLUMN users.deletion_requested_at IS
    'When the user asked to close the account. Cleared if they cancel within the grace period.';
COMMENT ON COLUMN users.deletion_scheduled_at IS
    'When anonymisation falls due. The date promised to the user; not recomputed.';
COMMENT ON COLUMN users.anonymised_at IS
    'When the identifying columns were overwritten. Set once; the overwrite is not reversible.';
