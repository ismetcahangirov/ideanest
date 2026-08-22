-- §9.6's retry schedule (#64, #65), on the row it is a schedule for.
--
-- ---------------------------------------------------------------------------
-- Reverse:
--   ALTER TABLE pledges
--       DROP CONSTRAINT IF EXISTS pledges_collection_schedule_is_whole,
--       DROP CONSTRAINT IF EXISTS pledges_charge_attempts_is_not_negative,
--       DROP CONSTRAINT IF EXISTS pledges_charge_window_follows_the_queue;
--   DROP INDEX IF EXISTS pledges_due_for_charge_idx;
--   ALTER TABLE pledges
--       DROP COLUMN IF EXISTS charge_attempts,
--       DROP COLUMN IF EXISTS next_charge_attempt_at,
--       DROP COLUMN IF EXISTS charge_window_ends_at;
--
--   Safe only while no campaign is mid-collection, and that is a real
--   qualification rather than a formality. These three columns are the whole of
--   where a campaign *is* in its seven days: how many attempts a pledge has had,
--   when the next one is due, and when the platform stops trying. Dropping them
--   with pledges in `CHARGE_PENDING` or `CHARGE_FAILED` does not lose money --
--   `transactions` still holds every attempt -- but it does lose the schedule, and
--   the previous build has no code that would resume it. So: let the collecting
--   campaigns finish, or accept that their remaining retries have to be
--   reconstructed from `transactions.attempt_number` by hand.
--
--   Those `DROP COLUMN`s are the documented reverse and not part of the forward
--   change: this migration only adds, and the release that ran them would be the
--   release that had already stopped collecting.
-- ---------------------------------------------------------------------------
--
-- ---------------------------------------------------------------------------
-- WHY THE SCHEDULE IS ON THE PLEDGE
-- ---------------------------------------------------------------------------
--
-- The obvious alternative is a `collection_attempts` table, one row per scheduled
-- attempt, and it was rejected for the reason `deadline_notices` was *chosen*: the
-- shapes look alike and the questions are opposite.
--
-- `deadline_notices` exists because its sweep's question -- "is this campaign
-- within 48 hours of closing" -- stays true for two days, so a claim row is the
-- only thing that stops the announcement being repeated every tick. A
-- collection's question is "is this pledge due", and it stops being true the
-- instant the attempt is made, because the attempt moves `next_charge_attempt_at`
-- forward. The claim is therefore the conditional update itself, and a second
-- table would be a row per attempt whose only reader is a report `transactions`
-- already answers better -- with the provider's decline code on it.
--
-- What the pledge does *not* keep is the history. `charge_attempts` is a count and
-- not a log; "the third attempt was refused for insufficient funds" is
-- `transactions`, which V41 made append-only precisely so that this column can be
-- a simple counter without anybody having to trust it as evidence.
--
-- ---------------------------------------------------------------------------
-- WHY A DEADLINE COLUMN RATHER THAN AN ARITHMETIC ON THE CAMPAIGN
-- ---------------------------------------------------------------------------
--
-- §9.6 drops a pledge seven days after the campaign closed, and
-- `projects.finalized_at` already records when that was. `charge_window_ends_at`
-- is stored anyway, and the reason is the same one V29 gives for freezing the
-- outcome: **the rule is configuration, and configuration changes.** A window
-- computed on every read from `ideanest.payment.collection.retry-window` would
-- silently move every in-flight campaign's deadline the day somebody shortened
-- it -- lengthening it is merely surprising, shortening it drops pledges that were
-- still inside their window when the platform last told the backer about them.
-- Frozen when the pledge is queued, it is a promise the platform made to one
-- backer and can be read back as one.
--
-- ---------------------------------------------------------------------------
-- Rolling deployment
-- ---------------------------------------------------------------------------
--
-- Three nullable-or-defaulted columns, one partial index, three check
-- constraints, and no column dropped or narrowed. The previous release does not
-- know these columns exist, and -- more to the point -- it cannot violate the
-- constraints, because all three are conditional on `state` being
-- `CHARGE_PENDING` or `CHARGE_FAILED` and no release before this one has any code
-- that reaches either state. `charge_attempts` takes a default rather than being
-- nullable so that the older build's `INSERT` of a draft, which does not name the
-- column, still produces a row the newer build can count from.
--
-- `ADD COLUMN ... DEFAULT 0` does not rewrite the table on PostgreSQL 11 or
-- newer; the default is stored in the catalogue and materialised on write. On a
-- `pledges` table of any size this is a metadata change. This is an EXPAND with no
-- contract half.

ALTER TABLE pledges
    -- **How many of §9.6's four attempts have been made.** Zero on every pledge
    -- that exists today and on every draft written from now on; the count starts
    -- moving when `charge-processor` queues the pledge.
    --
    -- Counted rather than derived from `transactions`, and the difference matters
    -- under contention: the count has to be read and advanced in the same
    -- conditional update that claims the pledge for an attempt, and an aggregate
    -- over another table cannot be part of that statement's `WHERE`.
    ADD COLUMN charge_attempts        integer     NOT NULL DEFAULT 0,

    -- **When the next attempt may be made.** §9.6's timings, resolved to instants
    -- when the previous attempt failed: immediately, +24 hours, +72 hours, +5 days.
    --
    -- Null on anything that is not queued for collection, which is every pledge
    -- until its campaign succeeds and every pledge after it is collected or
    -- dropped. The partial index below reads exactly the rows where it is not.
    --
    -- Written by the application from the injected `Clock` and never defaulted
    -- here, for V17's, V18's and V19's reason: the schedule is a rule, a rule needs
    -- one home, and a test has to be able to ask what happens in five days without
    -- waiting five days.
    ADD COLUMN next_charge_attempt_at timestamptz,

    -- **When the platform stops trying**, frozen when the pledge is queued. See the
    -- header for why this is a column and not `finalized_at + retry-window`.
    ADD COLUMN charge_window_ends_at  timestamptz;

ALTER TABLE pledges
    ADD CONSTRAINT pledges_charge_attempts_is_not_negative CHECK (charge_attempts >= 0),

    -- **A pledge queued for collection has a schedule; one that is not has none.**
    -- Both directions, because each failure is real and different. Without the
    -- first, a `CHARGE_PENDING` pledge with no `next_charge_attempt_at` is invisible
    -- to the sweep and is never charged, never dropped, and never noticed -- the
    -- worst outcome in this feature, because the campaign's payout is quietly short
    -- and nothing anywhere says why. Without the second, a `COLLECTED` pledge that
    -- kept its schedule would be picked up by the next pass and charged again.
    ADD CONSTRAINT pledges_collection_schedule_is_whole CHECK (
        (state IN ('CHARGE_PENDING', 'CHARGE_FAILED'))
            = (next_charge_attempt_at IS NOT NULL AND charge_window_ends_at IS NOT NULL)
    ),

    -- The window cannot end before the attempt it is supposed to bound. A row that
    -- says so is a pledge that will be dropped at the moment it becomes due, which
    -- reads to the backer as never having been tried.
    ADD CONSTRAINT pledges_charge_window_follows_the_queue CHECK (
        charge_window_ends_at IS NULL
        OR next_charge_attempt_at IS NULL
        OR charge_window_ends_at >= next_charge_attempt_at
    );

-- **The sweep's index**, partial over the two states that owe an attempt and
-- ordered by when they owe it. `charge-processor` runs every minute and
-- `charge-retry` every six hours; between them they ask this one question, and
-- the partial predicate is what keeps the answer cheap on a table that will hold
-- every pledge the platform has ever taken. Everything collected, dropped or
-- cancelled has left this index.
CREATE INDEX pledges_due_for_charge_idx
    ON pledges (next_charge_attempt_at, id)
    WHERE state IN ('CHARGE_PENDING', 'CHARGE_FAILED');

COMMENT ON COLUMN pledges.charge_attempts IS
    '§9.6: how many collection attempts have been made, counted from zero. The history is transactions; this is the counter the claim advances.';
COMMENT ON COLUMN pledges.next_charge_attempt_at IS
    '§9.6: when the next attempt may be made. Null unless the pledge is CHARGE_PENDING or CHARGE_FAILED.';
COMMENT ON COLUMN pledges.charge_window_ends_at IS
    '§9.6: when the platform stops trying and the pledge is dropped. Frozen when the pledge is queued, so reconfiguring the window does not move a promise already made.';
