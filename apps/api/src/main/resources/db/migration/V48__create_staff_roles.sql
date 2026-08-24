-- §4.11's role model (#295): what a member of staff may do, expressed as
-- something other than one list of addresses.
--
-- ---------------------------------------------------------------------------
-- Reverse:
--   DROP TABLE IF EXISTS staff_role_grants;
--
--   Safe in the sense that nothing else references it, and lossy in the sense
--   that every grant an administrator has issued is gone and the platform falls
--   back to `IDEANEST_STAFF_BOOTSTRAP_EMAILS` -- which is the configured list
--   this migration exists to replace. A deployment that has moved its staff off
--   that variable and then reverses this has no staff at all until somebody
--   sets it again. Fail-closed, but fail-closed at three in the morning.
-- ---------------------------------------------------------------------------
--
-- ---------------------------------------------------------------------------
-- WHAT THIS TABLE IS FOR
-- ---------------------------------------------------------------------------
--
-- Until now "platform staff" was `ideanest.project.moderation.moderator-emails`
-- -- a comma-separated environment variable, checked by `ModeratorDirectory`,
-- and the same answer for every question. #295 states the problem in one line:
-- that list "cannot express 'may refund' against 'may moderate'".
--
-- It could not, and the cost was about to become concrete rather than
-- theoretical. This epic's remaining modules include a refund console, a payout
-- approval queue and a fee editor. With one list, the person hired to clear the
-- comment queue can move money, and the only way to stop them is to take away
-- the comment queue too.
--
-- So: an account holds zero or more roles, a role confers a set of
-- capabilities, and every privileged endpoint names the capability it needs.
--
-- ---------------------------------------------------------------------------
-- WHY ROLES IN THE TABLE AND CAPABILITIES IN THE CODE
-- ---------------------------------------------------------------------------
--
-- The obvious alternative is a `staff_capabilities` table -- one row per
-- account per capability -- and it is what `collaborator_capabilities` (V9)
-- does for a campaign's team. That shape is right there and it is deliberately
-- not copied, because the two are answering different questions.
--
-- A collaborator's grant is *authored by a user*: a creator decides, campaign
-- by campaign, that this person may edit rewards and not the story. There is no
-- fixed vocabulary of collaborator kinds, because there are as many arrangements
-- as there are teams, so the grant has to be per capability.
--
-- A staff grant is *authored by the operator* and there are four kinds of
-- person: somebody who works the queues, somebody who curates the front page,
-- somebody who works with money, and somebody who runs the platform. Storing
-- those as capability sets would mean that adding a capability -- and this
-- migration ships beside eleven new console modules that each add one -- is a
-- data migration over every staff row, in which the rows that were missed are
-- invisible until somebody cannot do their job.
--
-- Roles in the column, the capability set in `StaffRole`: adding a capability
-- to a role is a code change, reviewed, and it applies to everybody who holds
-- the role at the moment it deploys.
--
-- The trade this makes is that a role cannot be adjusted for one person without
-- a deployment. That is the right way round for a platform with four members of
-- staff. The day it is wrong, the fix is an exceptions table beside this one
-- -- grants and denials on top of a role -- and not a rewrite of it.
--
-- ---------------------------------------------------------------------------
-- WHY (account_id, role) AND NOT account_id ALONE
-- ---------------------------------------------------------------------------
--
-- One role per account is simpler and is wrong on the first day: on a platform
-- this size the same person clears the report queue in the morning and
-- reconciles a chargeback in the afternoon. A single-role column forces that
-- person into ADMINISTRATOR, which hands them the fee editor as well -- and the
-- whole point of this table is to stop capability arriving as a side effect of
-- needing something adjacent.
--
-- So the primary key is the pair, an account may hold several, and the
-- capabilities it holds are the union. Union rather than intersection or
-- precedence because roles here are additive by construction: none of them
-- takes anything away, so there is nothing for a precedence rule to resolve.
--
-- ---------------------------------------------------------------------------
-- WHY granted_by IS NOT NULL AND REFERENCES users
-- ---------------------------------------------------------------------------
--
-- §17 requires that every privileged action be attributable, and granting
-- somebody the ability to issue refunds is the most privileged action on the
-- platform. The `audit_logs` row is written too -- `staff.role_granted` -- and
-- this column is deliberately not redundant with it: the audit table answers
-- "what happened", this answers "on whose authority does this grant stand
-- *now*", and the second question is the one asked when a grant is found that
-- nobody remembers making.
--
-- ON DELETE RESTRICT rather than SET NULL, so that closing the account of
-- somebody who granted roles is refused rather than quietly orphaning the
-- record of who let whom in. It is a deliberate obstacle: the grants they made
-- should be reviewed before their row goes.
-- ---------------------------------------------------------------------------

CREATE TABLE staff_role_grants (
    account_id uuid NOT NULL
        REFERENCES users (id) ON DELETE CASCADE,

    -- Text with a CHECK rather than a PostgreSQL enum type, which is what every
    -- other closed set on this platform does (V19's header has the argument):
    -- adding a value to an enum type cannot run inside the same transaction as
    -- the statements that use it, which makes a rolling deployment awkward for
    -- no gain over a constraint.
    role text NOT NULL
        CONSTRAINT staff_role_grants_known CHECK (
            role IN ('MODERATOR', 'CURATOR', 'FINANCE', 'ADMINISTRATOR')),

    granted_at timestamptz NOT NULL DEFAULT now(),

    granted_by uuid NOT NULL
        REFERENCES users (id) ON DELETE RESTRICT,

    -- Why this person holds this role, in the words of whoever granted it. Not
    -- required, unlike a suspension's reason: a suspension's reason is read back
    -- to the person it was done to and an appeal is answered from it, whereas
    -- this is a note to the next administrator. Required-but-empty is what a
    -- required field with no reader becomes.
    note text
        CONSTRAINT staff_role_grants_note_length CHECK (note IS NULL OR length(note) <= 2000),

    CONSTRAINT staff_role_grants_pkey PRIMARY KEY (account_id, role)
);

-- The one query on the hot path: "what may this caller do", asked on every
-- request to every console endpoint. Served by the primary key's leading
-- column, so no second index is needed for it.
--
-- The other query is the console's own staff list -- "who holds what" -- which
-- reads the whole table. There are four rows. It does not get an index.

COMMENT ON TABLE staff_role_grants IS
    'Which platform roles an account holds (#295). Capabilities are the union of the roles'' sets, defined in StaffRole.';
