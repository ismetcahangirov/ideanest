-- §4.11's AD-04 (#104): an account trust and safety has stopped.
--
-- ---------------------------------------------------------------------------
-- Reverse:
--   ALTER TABLE users DROP COLUMN suspension_reason;
--   ALTER TABLE users DROP COLUMN suspended_by;
--   ALTER TABLE users DROP COLUMN suspended_at;
--   -- Every suspension becomes an account that can sign in again, with no record
--   -- of why it could not. `audit_logs` still says who suspended whom and when,
--   -- so the decisions are recoverable by hand; nothing else reads these columns.
--   -- Not a contract half: nothing has been dropped by this migration, and the
--   -- reverse above is what an operator would run to undo it.
-- ---------------------------------------------------------------------------
--
-- ---------------------------------------------------------------------------
-- WHY NOT `deleted_at`, AND WHY NOT A `state` COLUMN
-- ---------------------------------------------------------------------------
--
-- V5 already gives this table a deletion lifecycle: `deletion_requested_at`,
-- `deletion_scheduled_at`, `anonymised_at`, `deleted_at`. A suspension is none of
-- those. Deletion is something a person asks for and §17.4 eventually anonymises;
-- a suspension is something the platform does *to* an account, it keeps every row
-- readable because an investigation needs them, and it is reversible -- a
-- suspension made in error has to be undoable, or the mistake is permanent.
--
-- A single `state` column covering both was rejected for that reason: the two are
-- orthogonal. An account can be suspended *and* inside its deletion grace period,
-- and a state column would force whoever wrote the update to choose which fact to
-- keep.
--
-- ---------------------------------------------------------------------------
-- THREE COLUMNS, ALL OR NONE
-- ---------------------------------------------------------------------------
--
-- The instant, who did it, and why. The reason is not optional: it is what the
-- person is told, what an appeal is answered from, and what somebody reviewing
-- the decision a year later reads. `suspended_by` has a real foreign key, unlike
-- the actor on `audit_logs` -- staff are users of this platform, the reference is
-- to the same table, and a suspension pointing at nobody is a decision with no
-- author.

ALTER TABLE users
    ADD COLUMN suspended_at      timestamptz,
    ADD COLUMN suspended_by      uuid REFERENCES users (id),
    ADD COLUMN suspension_reason text;

ALTER TABLE users
    ADD CONSTRAINT users_suspension_is_whole CHECK (
        (suspended_at IS NULL AND suspended_by IS NULL AND suspension_reason IS NULL)
        OR (suspended_at IS NOT NULL AND suspended_by IS NOT NULL
            AND length(btrim(suspension_reason)) BETWEEN 1 AND 2000)
    );

-- **An account cannot suspend itself**, which is not tidiness: the row would be
-- the only trace of a decision, and a self-reference makes "who did this" answer
-- with the person it was done to. Staff suspending their own account is a support
-- request, not an action.
ALTER TABLE users
    ADD CONSTRAINT users_suspension_has_another_author CHECK (suspended_by IS NULL OR suspended_by <> id);

-- The admin list's default filter -- "who is suspended" -- over a table where
-- almost nobody is. Partial for that reason: the index holds the exceptions.
CREATE INDEX users_suspended_idx ON users (suspended_at DESC) WHERE suspended_at IS NOT NULL;

COMMENT ON COLUMN users.suspended_at IS
    'AD-04 (#104). Set by staff, reversible, and orthogonal to V5''s deletion lifecycle: an account can be both.';
COMMENT ON COLUMN users.suspension_reason IS
    'What the person is told and what an appeal is answered from. Required whenever suspended_at is set.';
