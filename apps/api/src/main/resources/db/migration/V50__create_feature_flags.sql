-- §4.11's AD-12 (#312): what is switched on, for whom, and how far.
--
-- ---------------------------------------------------------------------------
-- Reverse:
--   DROP TABLE IF EXISTS feature_flags;
--
--   Every flag falls back to its code default, which is `false` for everything
--   this release ships. That is the safe direction -- a reversed migration
--   switches features off rather than on -- but it is not a no-op: a flag that
--   was rolled out to everybody goes dark, and nobody is told.
-- ---------------------------------------------------------------------------
--
-- ---------------------------------------------------------------------------
-- WHAT THIS IS FOR
-- ---------------------------------------------------------------------------
--
-- §4.11 gives AD-12 two words: "gradual rollout, experiments". The store behind
-- them did not exist, which is what #312 was blocked on.
--
-- The shape is deliberately the smaller of the two obvious ones. A full
-- experimentation platform -- variants, metrics, significance -- is a product,
-- and the platform has no experiment to run yet. What it has is a need to ship a
-- half-finished surface to nobody, then to staff, then to a tenth of the
-- audience, and to turn it off from a screen rather than a deployment. So: a
-- flag is on, off, or on for a percentage, plus an explicit list of accounts
-- that always see it.
--
-- ---------------------------------------------------------------------------
-- WHY rollout_percentage AND A HASH RATHER THAN A SAMPLED SET
-- ---------------------------------------------------------------------------
--
-- "Ten percent of accounts" can be stored as a set of chosen accounts or
-- computed per request from a hash of (flag, account). The set is worse in the
-- way that matters: it has to be recomputed when the percentage moves, and every
-- recomputation reshuffles who is in -- so somebody who saw the feature
-- yesterday loses it today because the rollout went *up*. A hash is stable by
-- construction, and widening the rollout only ever adds people.
--
-- The hash is computed in `FeatureFlags`, not here, because it has to be the
-- same function on every read and a database expression would be a second copy.
--
-- ---------------------------------------------------------------------------
-- WHY enabled_accounts IS AN ARRAY AND NOT A JOIN TABLE
-- ---------------------------------------------------------------------------
--
-- Normally the join table wins and the array is the shortcut somebody regrets.
-- Here the array is right: the list is read in full on every evaluation, is
-- never queried from the other direction ("which flags is this account in" is
-- not a question anybody asks), holds a handful of staff accounts, and is
-- rewritten whole by the screen that edits it. A join table would add a query to
-- the hot path to answer a question that is always "give me all of them".
--
-- Bounded by a CHECK, because an unbounded array on a row read this often is how
-- a convenience becomes an incident.
-- ---------------------------------------------------------------------------

CREATE TABLE feature_flags (
    -- The flag's name is its identity. Code says `flags.isOn("checkout-v2", id)`,
    -- so a surrogate key would be a second name for the same thing and the two
    -- would disagree the first time somebody renamed one.
    key text PRIMARY KEY
        CONSTRAINT feature_flags_key_shape CHECK (key ~ '^[a-z][a-z0-9-]{1,62}[a-z0-9]$'),

    description text NOT NULL
        CONSTRAINT feature_flags_description_present CHECK (length(btrim(description)) BETWEEN 1 AND 2000),

    -- The master switch. Off means off for everybody including the accounts
    -- named below -- an explicit list is an exception to the percentage, not to
    -- the flag being disabled, because "I turned it off and it is still on for
    -- some people" is the worst possible property of a kill switch.
    enabled boolean NOT NULL DEFAULT false,

    rollout_percentage smallint NOT NULL DEFAULT 0
        CONSTRAINT feature_flags_rollout_in_range CHECK (rollout_percentage BETWEEN 0 AND 100),

    -- Always in, whatever the percentage says. Staff, and the two people
    -- testing.
    enabled_accounts uuid[] NOT NULL DEFAULT '{}'
        CONSTRAINT feature_flags_enabled_accounts_bounded CHECK (cardinality(enabled_accounts) <= 200),

    updated_at timestamptz NOT NULL DEFAULT now(),

    updated_by uuid NOT NULL REFERENCES users (id) ON DELETE RESTRICT
);

COMMENT ON TABLE feature_flags IS
    'Gradual rollout, as a row rather than a deployment (#312, AD-12).';
