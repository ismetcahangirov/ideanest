-- §4.11's AD-11 and §9 (#311): what the platform charges, as a row rather than a
-- constant.
--
-- ---------------------------------------------------------------------------
-- Reverse:
--   DROP TABLE IF EXISTS fee_schedules;
--
--   Lossy in a way that matters more than most. Every rate the platform has ever
--   charged is in these rows, and §22.1 makes "what did we charge in March" a
--   question with a regulatory answer. A payout calculated against a schedule
--   this migration deleted cannot be re-derived. Export the table before
--   reversing, or take the previous build back instead.
-- ---------------------------------------------------------------------------
--
-- ---------------------------------------------------------------------------
-- WHY A TABLE AND NOT CONFIGURATION
-- ---------------------------------------------------------------------------
--
-- §9 gives the platform a percentage and the processor a percentage plus a fixed
-- amount. Both are numbers, both change rarely, and the obvious home for them is
-- `application.yml` beside every other number this service holds.
--
-- That is where they were, and it is wrong for one reason: a fee is not a
-- setting, it is a *term*. A pledge collected in March was collected under
-- March's terms, and if the rate lives in configuration then changing it
-- silently rewrites what every past payout should have been -- there is no
-- record that the number was ever anything else, and a payout recalculated after
-- a deployment disagrees with the one that was paid. §22.1 asks that question
-- with a seven-year retention rule attached to the answer.
--
-- So a schedule is a row with a window, an effective schedule is the one whose
-- window contains the moment being priced, and changing the fee means writing a
-- new row rather than editing an old one.
--
-- ---------------------------------------------------------------------------
-- WHY effective_from/effective_to AND NOT A SINGLE `active` FLAG
-- ---------------------------------------------------------------------------
--
-- A boolean would answer "what do we charge now" and nothing else, which is the
-- one question that does not need a table. The window answers "what did we
-- charge then", which is the question a reconciliation asks.
--
-- `effective_to` is null for the schedule currently in force. Closing one and
-- opening the next is two statements in one transaction, which is why
-- `FeeScheduleService.replace` exists rather than an update endpoint.
--
-- ---------------------------------------------------------------------------
-- WHY scope AND scope_ref RATHER THAN A NULLABLE category_id
-- ---------------------------------------------------------------------------
--
-- §4.11 says "platform and processing rates with exceptions", and the exceptions
-- named in §9 are per category and per campaign. A nullable `category_id` would
-- carry the first and not the second, and adding the second later would mean a
-- second nullable column and a CHECK asserting that at most one of them is set
-- -- which is this shape, arrived at with two migrations instead of one.
--
-- So: `scope` says what kind of thing the exception is about and `scope_ref`
-- says which one, null for the platform-wide default. No foreign key on
-- `scope_ref`, deliberately: it points into different tables depending on
-- `scope`, and the alternative is one nullable FK column per scope kind. What is
-- lost is referential integrity on an exception whose subject was deleted, and
-- what that costs is an exception that stops matching anything -- so the pricing
-- falls back to the platform default, which is the safe direction.
--
-- ---------------------------------------------------------------------------
-- WHY numeric AND NOT double precision
-- ---------------------------------------------------------------------------
--
-- CLAUDE.md: never use floating point for money. A rate is not money but it is
-- multiplied by money, and 0.05 has no exact binary representation -- so a
-- float rate turns an exact pledge into an inexact fee. numeric(6,5) holds a
-- percentage as a fraction with five decimal places, which is 0.00001 -- a
-- thousandth of a percent, finer than any rate anybody has proposed.
--
-- The fixed processing amount is numeric(19,4) in minor-unit-free decimal, which
-- is what `transactions.amount` (V41) uses. One shape for money across the
-- schema.
-- ---------------------------------------------------------------------------

CREATE TABLE fee_schedules (
    id uuid PRIMARY KEY,

    scope text NOT NULL
        CONSTRAINT fee_schedules_scope_known CHECK (scope IN ('PLATFORM', 'CATEGORY', 'PROJECT')),

    -- Which category or which campaign. Null exactly when the scope is PLATFORM.
    scope_ref uuid,

    CONSTRAINT fee_schedules_scope_ref_matches_scope CHECK (
        (scope = 'PLATFORM' AND scope_ref IS NULL)
        OR (scope <> 'PLATFORM' AND scope_ref IS NOT NULL)),

    -- The platform's cut, as a fraction: 0.05000 is five percent.
    platform_rate numeric(6, 5) NOT NULL
        CONSTRAINT fee_schedules_platform_rate_sane CHECK (platform_rate >= 0 AND platform_rate <= 1),

    -- The processor's cut, same shape.
    processing_rate numeric(6, 5) NOT NULL
        CONSTRAINT fee_schedules_processing_rate_sane CHECK (processing_rate >= 0 AND processing_rate <= 1),

    -- The processor's fixed amount per transaction, in the platform currency.
    processing_fixed numeric(19, 4) NOT NULL DEFAULT 0
        CONSTRAINT fee_schedules_processing_fixed_sane CHECK (processing_fixed >= 0),

    currency text NOT NULL DEFAULT 'AZN'
        CONSTRAINT fee_schedules_currency_shape CHECK (currency ~ '^[A-Z]{3}$'),

    effective_from timestamptz NOT NULL,

    -- Null while this schedule is the one in force.
    effective_to timestamptz,

    CONSTRAINT fee_schedules_window_ordered CHECK (
        effective_to IS NULL OR effective_to > effective_from),

    -- Why the rate changed, for the person reading the history in a year. Not
    -- optional: a fee change with no stated reason is one nobody can defend, and
    -- unlike a staff grant's note this one is read by an auditor rather than by
    -- a colleague.
    note text NOT NULL
        CONSTRAINT fee_schedules_note_present CHECK (length(btrim(note)) BETWEEN 1 AND 2000),

    created_at timestamptz NOT NULL DEFAULT now(),

    created_by uuid NOT NULL REFERENCES users (id) ON DELETE RESTRICT
);

-- At most one open schedule per scope. A partial unique index rather than a
-- constraint because the rule is only about the open ones: a scope accumulates
-- as many closed windows as it has had rates, and two open ones would make
-- "what do we charge" ambiguous in the one query that has to answer it.
--
-- COALESCE because a unique index treats two NULLs as distinct, so
-- (PLATFORM, NULL) would not conflict with itself -- which is exactly the row
-- this index exists to keep unique.
CREATE UNIQUE INDEX fee_schedules_one_open_per_scope
    ON fee_schedules (scope, COALESCE(scope_ref, '00000000-0000-0000-0000-000000000000'::uuid))
    WHERE effective_to IS NULL;

-- The pricing lookup: "which schedule was in force for this scope at this
-- instant". Leads on the scope because that is what is always known.
CREATE INDEX fee_schedules_by_scope_and_window
    ON fee_schedules (scope, scope_ref, effective_from DESC);

COMMENT ON TABLE fee_schedules IS
    'What the platform and the processor charge, with a validity window (#311, AD-11, §9).';
