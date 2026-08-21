-- §4.10's "48 hours remaining", "24 hours remaining" and "saved project ending
-- soon" (#90): the record of which deadline notices a campaign has already had.
--
-- ---------------------------------------------------------------------------
-- WHY THERE HAS TO BE A TABLE AT ALL
-- ---------------------------------------------------------------------------
--
-- §8.4's deadline sweep asks "which live campaigns are within 48 hours of
-- closing". That question is true for the whole of the last two days, so a
-- sweep that acted on the answer would announce the same campaign on every
-- tick -- once a minute for two days -- and each announcement is a message to
-- every backer of it.
--
-- The state that stops it has to be durable and it has to be *claimed*, not
-- checked. A read-then-write in Java loses the race between two replicas
-- sweeping together: both see no notice, both record the event, and every
-- backer is told twice. So the claim is an INSERT whose conflict the database
-- decides, in the same transaction as the outbox row it authorises -- the
-- arrangement `reminders.notified_at` already uses for launch notices and
-- `CampaignFinalizer` uses for the outcome.
--
-- ---------------------------------------------------------------------------
-- WHY A ROW PER THRESHOLD, RATHER THAN COLUMNS ON `projects`
-- ---------------------------------------------------------------------------
--
-- The obvious alternative is `projects.notified_48h_at` and
-- `projects.notified_24h_at`. Rejected on two counts:
--
--   * `projects` is the platform's widest table and its hottest row. Two more
--     columns written by a background sweep means the sweep contends with
--     every edit, every pledge counter update and every state transition on
--     precisely the campaigns that are busiest -- the ones about to close.
--   * A third threshold is then a migration over every campaign ever created.
--     Here it is a new value in a check constraint.
--
-- The cost of the separate table is one join in the sweep's query, which is an
-- anti-join against a table holding two rows per campaign that has ever come
-- near a deadline. That is cheap and it stays cheap.
--
-- ---------------------------------------------------------------------------
-- WHY IT IS NOT SWEPT, AND WHY IT CASCADES
-- ---------------------------------------------------------------------------
--
-- These rows are kept for the life of the campaign. They carry no personal
-- data -- a campaign, an integer and an instant -- so §17.4 has nothing to
-- reach here, and the thing they protect against is precisely a redelivery
-- long after the fact. Deleting them to save space would re-arm the duplicate
-- they exist to prevent.
--
-- They cascade with the campaign because a notice about a campaign that no
-- longer exists protects nothing.
--
-- ---------------------------------------------------------------------------
-- Reverse:
--   DROP TABLE IF EXISTS deadline_notices;
--   -- Reversing discards the record of which campaigns have been told their
--   -- deadline is near. Note what it costs, because it is not nothing: every
--   -- live campaign inside its last 48 hours is announced again on the next
--   -- sweep, and every backer of it is notified a second time. The outbox's
--   -- own idempotency does not help -- that deduplicates a redelivery of one
--   -- event, and this would be a genuinely new event about the same fact.
--   -- Reverse this only with the sweep stopped.
-- ---------------------------------------------------------------------------

CREATE TABLE deadline_notices (
    -- Cascades: see above.
    project_id       uuid        NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    -- How many hours were left when this notice became due: 48 or 24.
    --
    -- An integer with a check rather than an enum of two names, unlike the
    -- state vocabularies elsewhere in this schema. The difference is that these
    -- are quantities and they are compared as quantities -- the sweep asks for
    -- the threshold whose interval has elapsed -- where a state is a name that
    -- happens to be spelled in capitals. `interval '1 hour' * threshold_hours`
    -- is a sentence; the same expression over an enum is a CASE.
    threshold_hours  integer     NOT NULL,
    -- When the sweep claimed this notice, which is also when the outbox event
    -- authorised by the claim was recorded. One transaction, so the two
    -- instants are the same fact and there is no second column for it.
    noticed_at       timestamptz NOT NULL DEFAULT now(),

    -- The claim. One notice per campaign per threshold, decided by the database
    -- because two replicas sweeping together is the normal state of affairs.
    CONSTRAINT deadline_notices_pkey PRIMARY KEY (project_id, threshold_hours),

    -- The vocabulary, stated here so that a support script cannot invent a
    -- threshold nothing sends and no reader has to guess what 36 would have
    -- meant. Widening it is one line and a release of the sweep that acts on
    -- the new value -- in that order, for `ProjectAudience`'s reason.
    CONSTRAINT deadline_notices_threshold_known CHECK (threshold_hours IN (24, 48))
);

-- The sweep's anti-join runs on the primary key above and needs no index of its
-- own. Stated rather than left out silently: the pattern in this schema is an
-- index per query, and the exception here is that the query's predicate is
-- exactly the primary key.

COMMENT ON TABLE deadline_notices IS
    'Which deadline notices a campaign has already been sent. The row is the claim: it is inserted in the same transaction as the outbox event it authorises.';
COMMENT ON COLUMN deadline_notices.threshold_hours IS
    'Hours remaining when the notice became due: 48 or 24. Widening the vocabulary is a migration and a release of the sweep, in that order.';
