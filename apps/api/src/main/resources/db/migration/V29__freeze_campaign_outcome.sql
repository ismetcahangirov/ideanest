-- §5.1's all-or-nothing decision, and §8.4's `campaign-finalizer` (#63): the four
-- numbers that decided a campaign's fate, written once at the deadline and never
-- again.
--
-- ---------------------------------------------------------------------------
-- WHY THE OUTCOME IS COPIED RATHER THAN READ
-- ---------------------------------------------------------------------------
--
-- `projects.pledged_amount` and `projects.backers_count` already hold what a
-- campaign has raised, and at the instant the deadline passes they hold exactly
-- what §5.1 compares against the goal. The temptation is therefore to store
-- nothing: the state says SUCCESSFUL, and whoever wants to know why can read
-- those two columns.
--
-- That is wrong the moment collection starts, and #63's own sentence says why —
-- *later collection failures reduce the payout, never the outcome*. V6 documents
-- both columns as denormalised from the pledge ledger, which stays the source of
-- truth, and that ledger keeps moving after the deadline: a card is refused, a
-- retry window elapses, a pledge is DROPPED (§6.2), a charge is refunded or
-- charged back (§9.7, §9.8). Every one of those reduces the live total. A
-- campaign that closed at 105% of its goal and then lost eight percent of its
-- collections would, read that way, appear to have failed — and would appear to
-- have failed *retroactively*, on a page a backer is looking at, weeks after
-- being told it succeeded.
--
-- So the decision is a fact with a timestamp, not a comparison anybody can redo.
-- These columns are the evidence for a state that is otherwise unexplainable
-- after the fact, and they are the numbers §4.4 shows on a closed campaign's
-- page.
--
-- `outcome_goal_amount` is copied for the same reason and not for symmetry. §5.3
-- freezes the goal at launch, so it should not move — but "should not" is a rule
-- enforced by `ProjectEditLocks` in the application, and the whole point of this
-- row is that it survives being read years later by somebody who does not trust
-- the application that wrote it. A comparison recorded with only one of its two
-- sides is a comparison nobody can check.
--
-- ---------------------------------------------------------------------------
-- WHAT IS DELIBERATELY NOT CONSTRAINED
-- ---------------------------------------------------------------------------
--
-- There is no CHECK tying `finalized_at` to the state. It is tempting — every
-- state from SUCCESSFUL onwards is reached through the finaliser, so every such
-- row ought to carry an outcome — and it would be a constraint that describes
-- how the rows *arrive* rather than what they *mean*. A campaign's state is set
-- by `ProjectTransitionService` in the same transaction as the freeze, which is
-- what actually guarantees they agree; a CHECK would add nothing to that and
-- would make every seeded row, every import, and every future backfill of
-- historical campaigns a migration problem before it was a data problem.
--
-- What IS constrained is that the four move together, because a half-written
-- outcome is the one shape that could be read as a real decision and is not.
-- `num_nonnulls` states it in one expression rather than as four pairwise
-- equalities that a fifth column would silently escape.

ALTER TABLE projects
    -- When §5.1 was applied. Null for every campaign whose deadline has not
    -- passed, which is every campaign that is not yet in a terminal-or-later
    -- state, and the column `campaign-finalizer` claims a row on: the sweep's
    -- conditional update writes it, so exactly one replica finalises a campaign
    -- however many of them wake up together.
    ADD COLUMN finalized_at           timestamptz,
    -- The goal the total was compared against, as it stood at the deadline.
    ADD COLUMN outcome_goal_amount    numeric(14, 2),
    -- What the campaign had raised at the deadline. NOT what it later collected —
    -- see the header. §9.5's money flow is a separate story told by the ledger.
    ADD COLUMN outcome_pledged_amount numeric(14, 2),
    -- How many people were behind that total. The number §4.4 puts on a closed
    -- campaign's page, and the one a creator's report is checked against.
    ADD COLUMN outcome_backers_count  int,

    ADD CONSTRAINT projects_outcome_frozen_together CHECK (
        num_nonnulls(
            finalized_at,
            outcome_goal_amount,
            outcome_pledged_amount,
            outcome_backers_count
        ) IN (0, 4)
    ),

    -- A campaign that raised nothing is a real outcome; a campaign that raised a
    -- negative amount is a corrupted one, and the deadline is the last moment
    -- anybody would notice.
    ADD CONSTRAINT projects_outcome_not_negative CHECK (
        outcome_goal_amount    >= 0
        AND outcome_pledged_amount >= 0
        AND outcome_backers_count  >= 0
    );

COMMENT ON COLUMN projects.finalized_at IS
    'When §5.1 decided this campaign. Written once, by campaign-finalizer, in the '
    'same transaction as the LIVE -> SUCCESSFUL or LIVE -> UNSUCCESSFUL transition.';

COMMENT ON COLUMN projects.outcome_pledged_amount IS
    'What the campaign had raised when it closed. Frozen: pledged_amount keeps '
    'moving as collections fail, and a later failure reduces the payout, never '
    'the outcome.';

-- No new index. `projects_state_deadline_idx` on (state, deadline) has existed
-- since V6 and is exactly the index the sweep wants: state = 'LIVE' AND deadline
-- <= now(), oldest first. The claim then re-checks `finalized_at IS NULL` on the
-- row it locked, which is a predicate on one row rather than one the planner has
-- to help with.

-- Reverse:
--   ALTER TABLE projects
--       DROP CONSTRAINT IF EXISTS projects_outcome_not_negative,
--       DROP CONSTRAINT IF EXISTS projects_outcome_frozen_together,
--       DROP COLUMN IF EXISTS outcome_backers_count,
--       DROP COLUMN IF EXISTS outcome_pledged_amount,
--       DROP COLUMN IF EXISTS outcome_goal_amount,
--       DROP COLUMN IF EXISTS finalized_at;
--
--   Structurally clean and materially lossy, and the difference matters more here
--   than for most reversals. Nothing references these columns and no other table
--   is touched, so the statement above always succeeds. What it destroys is the
--   only record of why each closed campaign closed the way it did: the state
--   survives, the evidence does not, and it cannot be recomputed — `pledged_amount`
--   has moved on by then, which is the entire reason these columns exist.
--
--   So reversing this is safe on a deployment where no campaign has been finalised
--   yet, and is a permanent loss on one where any has. Before running it, copy the
--   four columns out:
--
--     CREATE TABLE projects_outcome_backup AS
--         SELECT id, finalized_at, outcome_goal_amount, outcome_pledged_amount,
--                outcome_backers_count
--           FROM projects WHERE finalized_at IS NOT NULL;
--
--   The forward migration then reapplies cleanly and the backup is a plain UPDATE.
--   Rolling back the application without rolling back this migration is the safer
--   move in every case: the columns are additive and the previous release neither
--   writes nor reads them.
