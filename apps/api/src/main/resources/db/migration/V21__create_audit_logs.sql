-- §7.2's `audit_logs` and §17's AD-14 (#107): what a privileged action leaves
-- behind, in a table the application can add to and cannot change.
--
-- ---------------------------------------------------------------------------
-- WHAT THIS TABLE IS FOR
-- ---------------------------------------------------------------------------
--
-- CLAUDE.md §3 says "every privileged action is audited", and until this
-- migration that was true of exactly one kind of action. A campaign's state
-- changes are recorded in `project_state_transitions`, and nothing else is:
-- `CollaboratorService` says so in its own class comment — issuing, widening and
-- withdrawing a grant are written to a log file and nowhere durable — and so do
-- the account and two-factor paths, which write nothing at all. A log file is not
-- an audit trail. It is rotated, it is not queryable by entity, and the retention
-- on it is whatever the platform's log shipper was configured with last.
--
-- `project_state_transitions` also cannot be made to hold these rows even if
-- somebody wanted it to: its check constraints accept the sixteen project states
-- and nothing else, and "two-factor authentication was switched off" is not a
-- campaign state.
--
-- So this is the general table. One row per privileged action, carrying the seven
-- facts an investigation actually asks for — who, on whose behalf, what, to what,
-- when, from where, and how it came out — plus the correlation identifier that
-- joins the row to §18.1's log lines for the same request.
--
-- ---------------------------------------------------------------------------
-- APPEND-ONLY, AND ENFORCED RATHER THAN AGREED
-- ---------------------------------------------------------------------------
--
-- A table the application could UPDATE or DELETE is not an audit log; it is a
-- table that happens to contain history until the afternoon somebody needs it not
-- to. The rule is therefore held by the database:
--
--   * **A statement-level BEFORE trigger** (`audit_logs_is_append_only`) that
--     raises on UPDATE, DELETE and TRUNCATE. Chosen over the two alternatives.
--
--     A rewrite rule (`ON UPDATE DO INSTEAD NOTHING`) would make the statement
--     succeed and change nothing, which is the worst of the three: an operator
--     correcting a row would be told it worked. Silence is what an audit trail
--     must never answer with.
--
--     A revoked grant (`REVOKE UPDATE, DELETE ON audit_logs FROM <app_role>`) is a
--     good second layer and belongs in deployment, not here, for two reasons. It
--     names a role this migration does not know — the role differs per
--     environment, and locally the application connects as the table's owner,
--     whom a REVOKE does not bind. And it is environment state rather than schema:
--     a restore, a `pg_dump | psql` into a new cluster, or a fresh review app
--     comes back without it, silently. A trigger travels with the table wherever
--     the table goes, and binds the owner and the superuser too.
--
--   * **Statement level, not row level.** `DELETE FROM audit_logs WHERE false`
--     matches nothing, so a row-level trigger would let it succeed and report
--     "0 rows" — and TRUNCATE triggers may only be statement level anyway. What
--     must not exist is the *statement*, so the statement is what is refused.
--
-- The one thing the trigger deliberately does not stop is DROP TABLE and
-- DETACH PARTITION. That is not an oversight; it is the retention path, below.
--
-- ---------------------------------------------------------------------------
-- HOW A ROW EVER LEAVES, GIVEN THAT NOTHING MAY DELETE ONE
-- ---------------------------------------------------------------------------
--
-- §7.3 lists this table among the monthly-partitioning candidates, and that is
-- the answer to "so how does a retention policy work". **Rows leave a month at a
-- time, by detaching the partition that holds them and dropping it** — never by
-- DELETE. Detaching is a catalogue operation on the parent, so the trigger does
-- not apply to it and does not need an exception carved out of it: the difference
-- between "a policy removed a whole month" and "somebody removed a row" stays
-- visible, which is the entire distinction this table exists to preserve.
--
-- **The table is not partitioned yet, and that is deliberate.** Three reasons,
-- all of which have to stop being true first:
--
--   1. Partitions have to be created ahead of time by something, and the durable
--      scheduler that would own that job is #134. A partitioned table that runs
--      past its last partition refuses the insert — and an audit write that fails
--      is precisely the outcome #107 exists to prevent. A DEFAULT partition avoids
--      the refusal, but then every monthly partition attached afterwards has to
--      scan the default for rows belonging to its range, which turns a routine
--      attach into a lock on the whole table.
--   2. A statement-level trigger on a partitioned parent does not fire for a
--      statement aimed at a partition directly. `DELETE FROM audit_logs_2026_08`
--      would work. Partitioning therefore *weakens* the append-only guarantee
--      until whatever creates each partition also puts this trigger on it — which
--      is the same job that does not exist yet.
--   3. Nothing is old enough to remove. How long a privileged action must be
--      retained is question "Personal data" in §22.1 and is unanswered; §17.4 keeps
--      financial records "for the statutory period" and nobody has said what that
--      is. Partitioning a table for a policy nobody has written is guessing.
--
-- Converting later is one migration and is cheap while the table is small: rename
-- `audit_logs` to the first partition's name, add the CHECK that matches its
-- bound, create the partitioned parent under the old name, ATTACH. The one real
-- cost is that a partitioned table's primary key must contain the partition key,
-- so `id` becomes `(id, occurred_at)` — an index rebuild, not a table rewrite.
--
-- ---------------------------------------------------------------------------
-- NO FOREIGN KEYS, AND WHY NOT
-- ---------------------------------------------------------------------------
--
-- `entity_id` names rows in `projects`, `users`, `collaborators` and `sessions`,
-- and no single foreign key can point at four tables — V19 makes the same
-- argument about `outbox_events.aggregate_id`. `actor_id` *could* reference
-- `users`, and it deliberately does not, because a reference on an append-only
-- table is a reference that can never be released: the audit row cannot be
-- deleted, so from the first audited action onwards the account row could not be
-- deleted either, in any environment, for ever. §17.4 anonymises an account in
-- place rather than deleting it, so production would survive that; a restore of a
-- subset, a fixture teardown, and a support script would not. And the constraint
-- buys nothing the row does not already have — the identifier is a UUID this
-- service minted, and the record of what somebody did stays true after the
-- account that did it stops naming a person.
--
-- ---------------------------------------------------------------------------
-- PERSONAL DATA
-- ---------------------------------------------------------------------------
--
-- The actor is a UUID and never an address or a name, for
-- `AccountCorrelationFilter`'s reason: a pseudonymous identifier joins the record
-- to a person's rows without putting the person in the record. `detail` and
-- `user_agent` are passed through §17.4's `Redaction` on the way in, so a caller
-- that hands over an exception message quoting an address does not turn this
-- table into the log line §17.4 forbids, and both are bounded in length —
-- attacker-controlled text going into a table nothing may prune row by row is
-- otherwise unbounded growth for the price of one header.
--
-- `source_address` is stored unmasked, and it is the one deliberate exception.
-- "From where" is half of what makes an audit row worth having, and §17.4's
-- anonymisation — which nulls `sessions.ip_address` — cannot reach here, because
-- the table refuses UPDATE. That is the intended trade and not an accident: an
-- audit trail that an account can erase by closing itself is not an audit trail.
-- The address leaves when its month leaves, which is the retention path above.
--
-- ---------------------------------------------------------------------------
-- Rolling deployment
-- ---------------------------------------------------------------------------
--
-- One new table, one new function, one new trigger, four new indexes, and nothing
-- existing touched. No column is dropped, no constraint is added to an existing
-- table, and no previous release reads or writes anything here — the older build
-- does not know the table exists and the newer one is its only writer. Both
-- halves of a rolling deploy are safe in either order. This is an EXPAND with no
-- contract half.
--
-- Reverse:
--   DROP TRIGGER IF EXISTS audit_logs_is_append_only ON audit_logs;
--   DROP TABLE IF EXISTS audit_logs;
--   DROP FUNCTION IF EXISTS audit_logs_refuse_change();
--
--   DROP TABLE would take the trigger with it; the first line is written out
--   anyway so that the reverse is the same three statements whether or not a
--   previous attempt left the table behind.
--
--   Safe while nothing writes here, which is true of every release before this
--   one. Afterwards it costs the thing the table is for, and that is worth saying
--   plainly rather than calling it a tidy-up: **dropping this discards the only
--   durable record of who did what.** Those rows exist nowhere else — a log file
--   is not a copy of them, it is a rotation schedule — so there is no cheap way
--   to reverse this, only a deliberate one.

CREATE TABLE audit_logs (
    -- UUID v7, minted by the application like every other key here.
    id              uuid        PRIMARY KEY,

    -- **When it happened, assigned by the database.**
    --
    -- A DEFAULT rather than a value the writer supplies, unlike the instants V17,
    -- V18 and V19 insist the application pass in. Those drive rules — a
    -- reservation's expiry, a retry's backoff — and a rule about time needs one
    -- home and a Clock a test can move. This one drives nothing; it records a fact
    -- about the row. And on this table specifically, taking it from the database
    -- means the instant on an audit record is not a value any caller chose, which
    -- is worth more than the testability the other three needed.
    occurred_at     timestamptz NOT NULL DEFAULT now(),

    -- **Who acted**, as a kind and an identifier.
    --
    -- The kind is not redundant with the identifier: SYSTEM has none, and telling
    -- "the nightly anonymiser did this" apart from "an account did this" is the
    -- first question asked of any row. MODERATOR is separate from USER because
    -- staff authority is the authority worth counting — until epic #100 gives the
    -- platform a role model, `ideanest.project.moderation.moderator-emails` is
    -- what decides it, and a row that recorded only "a user" would lose the one
    -- fact that made the action privileged.
    actor_type      text        NOT NULL,
    actor_id        uuid,

    -- **On whose behalf, when the actor was impersonating somebody.**
    --
    -- Null on every row this release can write, and the column exists anyway.
    -- AD-04 calls for "audited impersonation" and epic #100 (#104) builds it; the
    -- audit trail is the half that has to exist *first*, because impersonation
    -- shipped against a table with nowhere to put the subject would mean staff
    -- acting as a backer and the row saying only that the backer acted. Adding a
    -- column to a table nothing may UPDATE is also the cheapest possible
    -- migration, and adding a *meaning* to rows already written is not: every row
    -- inserted before it would be indistinguishable from a genuine non-
    -- impersonation, for ever.
    on_behalf_of_id uuid,

    -- What was done, in the vocabulary an operator reads: `project.approved`,
    -- `two_factor.disabled`. Not an enum type, for V19's reason about
    -- `event_type` — the set grows with every feature, and a new audited action
    -- must not be a migration and a deployment-ordering problem. The application's
    -- `AuditAction` is the closed set on the writing side.
    action          text        NOT NULL,

    -- **To what.** A type and an identifier rather than a foreign key; see the
    -- header. Required, both of them: a row that does not say what the action was
    -- done to answers "was this campaign approved" with a shrug.
    entity_type     text        NOT NULL,
    entity_id       uuid        NOT NULL,

    -- **How it came out.** SUCCEEDED, REFUSED, FAILED.
    --
    -- REFUSED is the reason this column is not a boolean and not absent. "Somebody
    -- tried to switch off a second factor and could not" is the row an
    -- investigation is looking for, and a table that only records what worked
    -- cannot produce it. It is also why `AuditLog` has a second, separately
    -- transacted entry point: a refusal is normally recorded by a transaction that
    -- is about to roll back.
    outcome         text        NOT NULL,

    -- **From where.** `inet` and not text, for V2's reason on `sessions`: it
    -- validates, it normalises IPv6, and it makes "everything done from this
    -- network" a query rather than string comparison. Null for work with no
    -- request behind it — a scheduled sweep has no client.
    --
    -- What is stored is the address the container saw, never `X-Forwarded-For`;
    -- `AuthController` has the argument, and it is stronger here. A header any
    -- client can set is a header an attacker sets, and an audit row naming an
    -- address of the attacker's choosing is worse than one naming none.
    source_address  inet,
    user_agent      text,

    -- **§18.1's identifiers, so the row and the request's log lines join.**
    --
    -- The row says what was decided; the lines say what the code was doing while
    -- it decided. Neither is much use during an incident without the other, and
    -- correlating them by timestamp is guesswork on a service handling more than
    -- one request per millisecond.
    request_id      text,
    trace_id        text,

    -- Whatever the call site could say that the columns above cannot: which
    -- capabilities a grant carried, which states a transition ran between, why a
    -- refusal was a refusal. Redacted and truncated by the application before it
    -- arrives.
    detail          text,

    CONSTRAINT audit_logs_actor_type_known CHECK (actor_type IN ('USER', 'MODERATOR', 'SYSTEM')),
    CONSTRAINT audit_logs_outcome_known CHECK (outcome IN ('SUCCEEDED', 'REFUSED', 'FAILED')),
    -- Both directions. An account-typed row with no account records that somebody
    -- did something, and a SYSTEM row carrying an account claims a job was a
    -- person. Either one is a row an investigation would read the wrong way.
    CONSTRAINT audit_logs_actor_is_named_unless_system CHECK (
        (actor_type = 'SYSTEM') = (actor_id IS NULL)
    ),
    -- An impersonation has two parties and they are different people. Without the
    -- first half the subject would be recorded with nobody acting; without the
    -- second, "acted as themselves" would be indistinguishable from the real
    -- thing and would inflate every count of it.
    CONSTRAINT audit_logs_impersonation_names_both_parties CHECK (
        on_behalf_of_id IS NULL OR (actor_id IS NOT NULL AND on_behalf_of_id <> actor_id)
    ),
    CONSTRAINT audit_logs_action_length CHECK (length(btrim(action)) BETWEEN 1 AND 128),
    CONSTRAINT audit_logs_entity_type_length CHECK (length(btrim(entity_type)) BETWEEN 1 AND 64)
);

-- **The refusal.** Raised, never swallowed: a caller that tried to change history
-- is told, at the statement, with the operation named.
--
-- `restrict_violation` rather than a bare `raise_exception`, so the class-23
-- SQLSTATE puts it where the driver and Spring already map integrity failures.
-- Whoever meets it should meet the same kind of error a check constraint gives,
-- because it is the same kind of fact: the database refused a row it will not
-- hold.
CREATE FUNCTION audit_logs_refuse_change() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'audit_logs is append-only; % is refused', tg_op
        USING ERRCODE = 'restrict_violation',
              HINT = 'Rows leave by detaching the month that holds them, never by UPDATE, DELETE or TRUNCATE.';
END;
$$;

COMMENT ON FUNCTION audit_logs_refuse_change() IS
    'Refuses any statement that would change or remove an audit row. See V21 for why this is a trigger and not a revoked grant.';

CREATE TRIGGER audit_logs_is_append_only
    BEFORE UPDATE OR DELETE OR TRUNCATE ON audit_logs
    FOR EACH STATEMENT EXECUTE FUNCTION audit_logs_refuse_change();

-- "What has this account done", which is the question asked about a compromised
-- session and about a member of staff alike. Descending, because every reading of
-- this table starts at the most recent row.
CREATE INDEX audit_logs_actor_idx ON audit_logs (actor_id, occurred_at DESC);

-- "What has been done to this campaign, this account, this grant." The other
-- direction, and the one a support conversation starts from.
CREATE INDEX audit_logs_entity_idx ON audit_logs (entity_type, entity_id, occurred_at DESC);

-- The administrative list §10.2 reserves `GET /v1/admin/audit-logs` for. No
-- endpoint reads it yet — that surface belongs to epic #100 — but the index is
-- what makes the table's own ordering cheap, and adding it later would mean
-- building it over everything ever recorded.
CREATE INDEX audit_logs_occurred_at_idx ON audit_logs (occurred_at DESC);

-- "What was done in this person's name." Partial over a set that is empty in
-- this release and should stay small in every release after it, which is what
-- makes the question cheap to ask often against a table that will be large.
CREATE INDEX audit_logs_on_behalf_of_idx
    ON audit_logs (on_behalf_of_id, occurred_at DESC) WHERE on_behalf_of_id IS NOT NULL;

COMMENT ON TABLE audit_logs IS
    'AD-14 (#107): one row per privileged action. Append-only, enforced by audit_logs_is_append_only; rows leave by partition detach, never by DELETE.';
COMMENT ON COLUMN audit_logs.on_behalf_of_id IS
    'The account an impersonating actor was acting as. Null until #104 builds impersonation; the column exists first so that the feature cannot ship without the record.';
COMMENT ON COLUMN audit_logs.outcome IS
    'SUCCEEDED, REFUSED, or FAILED. A refused privileged action is recorded, which is why AuditLog can write outside the caller''s transaction.';
COMMENT ON COLUMN audit_logs.source_address IS
    'The address the container saw, never X-Forwarded-For. Deliberately not redacted, and therefore not reachable by §17.4 anonymisation; see V21.';
