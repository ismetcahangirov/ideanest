-- §7.2's `project_analytics_daily` and §8.4's `analytics-aggregator` (#95): one
-- indexed row per campaign per day, so that §4.7's dashboard reads a table the
-- size of the calendar instead of scanning every pledge a campaign ever took.
--
-- ---------------------------------------------------------------------------
-- WHAT IS BEING PRE-AGGREGATED, AND FROM WHERE
-- ---------------------------------------------------------------------------
--
-- The source is `referral_attributions`, which V24 created and this module owns.
-- One row per confirmed pledge, carrying the amount, the currency, the instant
-- the pledge was confirmed, and the source it was attributed to. That is exactly
-- the fact table a daily rollup needs, and it is the only one the analytics
-- module may read: `pledges` belongs to another module and
-- `ModuleBoundaryTests` forbids reaching for it, which is the same wall #94 hit
-- and answered by having the event carry the amount.
--
-- Two consequences, said plainly rather than discovered later:
--
--   * **The rollup carries whatever `referral_attributions` carries.** #238
--     started publishing `pledge.confirmed`, so that is one row per confirmed
--     pledge from the release before this one onwards, and this table aggregates
--     it from the first scheduled pass. An earlier draft of this header said
--     nothing produced the event and the rollup was therefore empty; that was
--     true when it was written and is not true now.
--   * **There is no new-versus-returning split here** (§4.7's CD-09), and there
--     is nowhere to compute one from. `referral_attributions` carries no backer
--     identifier *by design* — V24 spends a paragraph on why a creator's report
--     must not be able to name the person behind a source — and the only other
--     answer is `pledges.backer_id`, which is another module's column. CD-09
--     needs a signal that does not exist yet; inventing a column here that
--     nothing can fill would be worse than the gap.
--
-- ---------------------------------------------------------------------------
-- THE GRAIN, AND WHY "A DAY" IS A DECISION RATHER THAN A DETAIL
-- ---------------------------------------------------------------------------
--
-- The grain is **one row per campaign per calendar day in one platform time
-- zone**, `ideanest.analytics.aggregation.zone`, which defaults to `Asia/Baku`.
--
-- Three candidates were available and only one of them is defensible here:
--
--   * **UTC.** Simple, and wrong for the reader. The creator this dashboard is
--     for is in UTC+4, so a UTC "today" ends at four in the morning local time —
--     which is to say that every pledge taken between midnight and 04:00, the
--     tail of the evening when a campaign's traffic actually peaks, would be
--     reported on the previous day. A creator comparing the dashboard against
--     their own calendar would find it disagreed and would have no way to see
--     why.
--   * **The campaign's own zone.** The honest answer, and `projects` has no such
--     column (V6). Adding one is a campaign-editor change with a picker, a
--     default, and a migration of every existing row behind it; it is not this
--     issue's, and a per-campaign zone invented here would be a column nobody
--     sets.
--   * **The creator's zone.** Worse than either: the same campaign would report
--     different daily numbers to a creator and to a collaborator in another
--     country, and the two would be looking at the same screen.
--
-- So: one zone, configured in one place, used by the writer and by the reader,
-- and **frozen onto every row** in `time_zone`. That last part is V24's rule
-- about `referral_touches.expires_at` applied to the same class of problem — a
-- deployment that changes the zone must not silently re-label history it did not
-- recompute. A row says which zone bucketed it, the read side returns that zone
-- with the answer, and a mismatch is visible rather than inferred.
--
-- Azerbaijan has observed no daylight saving since 2016, so a day here is
-- twenty-four hours and midnight is unambiguous. The application still does the
-- boundary arithmetic through `ZoneId` rather than a fixed offset, so a zone
-- that does observe it would still produce correct buckets.
--
-- ---------------------------------------------------------------------------
-- RE-RUNNING, AND WHY THE UPSERT IS THE WHOLE OF THE IDEMPOTENCY
-- ---------------------------------------------------------------------------
--
-- A day's row is a **pure function of the attributions in it**: count them, sum
-- them, and take the running total from the campaign's first day. Nothing is
-- accumulated onto what was there before, so recomputing yesterday produces
-- yesterday, and recomputing it a hundred times produces it a hundred times.
-- `(project_id, day)` is the primary key and the conflict target, so a re-run is
-- an `ON CONFLICT DO UPDATE` and never a second row.
--
-- That is deliberately more work than carrying the previous day's cumulative
-- forward, which would be one row read instead of a range scan. The cheaper form
-- makes a re-run depend on a row the job itself wrote, so one bad pass becomes a
-- permanently wrong running total with nothing to notice it. The optimisation is
-- available the day the range scan is measured to be the bottleneck; it is not
-- taken on the strength of a guess.
--
-- **A day with no pledges gets no row.** Absence means "nothing happened", which
-- is a fact the read side can state, and the alternative — writing a zero row for
-- every campaign for every day — would grow the table by campaigns × days
-- whether or not anything ever happened, and would still be incomplete for
-- campaigns outside the re-rollup window. The cumulative columns are what make
-- the gaps harmless: a chart reads the running total from the days that exist.
--
-- ---------------------------------------------------------------------------
-- LATE-ARRIVING DATA
-- ---------------------------------------------------------------------------
--
-- A pledge can be attributed after its day has already been rolled up: the
-- outbox relay retries with a backoff, and `pledged_at` is the instant the pledge
-- was confirmed rather than the instant the event was delivered.
--
-- The answer is a **bounded re-rollup window**, not a promise. Every pass
-- recomputes today and the previous `ideanest.analytics.aggregation.re-rollup-window`
-- days — three by default, which is four orders of magnitude beyond the relay's
-- ten-minute backoff ceiling and its eight attempts. An attribution that lands
-- later than that does not move its day until somebody re-runs the range by hand,
-- and `AnalyticsRollupService.rollUp(from, to)` is that handle. Silence here
-- would be the worse answer; an unbounded "recompute everything, hourly" would be
-- the other one.
--
-- ---------------------------------------------------------------------------
-- ONE CURRENCY PER CAMPAIGN
-- ---------------------------------------------------------------------------
--
-- A day's row holds one amount and one currency, so a campaign holding
-- attributions in two currencies has no row that would be true. §7.3 says every
-- pledge on a campaign is in the campaign's currency and §21.2 says a conversion
-- rate is never the basis of a collection, so this cannot happen — and if it ever
-- does, the job skips that campaign and says so, rather than writing a number
-- that is the addition of two different kinds of thing. The same refusal
-- `ReferralReportService` makes at the sum, moved to where the row is written.
--
-- ---------------------------------------------------------------------------
-- Rolling deployment
-- ---------------------------------------------------------------------------
--
-- Two new tables and two new indexes on `referral_attributions`. Nothing is
-- dropped, no column is altered, no constraint is added to an existing table, and
-- no previous release reads or writes anything here. Both halves of a rolling
-- deploy are safe in either order. This is an EXPAND with no contract half.
--
-- The two indexes are created without CONCURRENTLY, which is what Flyway's
-- transactional migration requires, and that takes an ACCESS EXCLUSIVE lock on
-- `referral_attributions` for as long as the build runs. An earlier draft of this
-- header called that free on the grounds that the table was empty because nothing
-- published `pledge.confirmed`. **#238 published it**, so the honest statement is
-- that the table is *small* rather than empty: it gains one row per confirmed
-- pledge, starting one release before this one, on a platform that has not
-- launched. That is seconds of lock at the outside, and its only writer is an
-- outbox relay that retries with a backoff, so a blocked insert is delayed rather
-- than lost.
--
-- The consequence is for the next index rather than this one, and it is now real:
-- once this table carries a campaign's worth of history, an index added to it
-- needs CONCURRENTLY and therefore a migration that is not in a transaction.
--
-- Reverse:
--   DROP TABLE IF EXISTS project_analytics_daily_channels;
--   DROP TABLE IF EXISTS project_analytics_daily;
--   DROP INDEX IF EXISTS referral_attributions_rollup_idx;
--   DROP INDEX IF EXISTS referral_attributions_pledged_at_idx;
--
--   In that order — the channel rows reference the daily row. Losing both tables
--   costs nothing that cannot be rebuilt: every column here is derived from
--   `referral_attributions`, which is not touched, so the reverse of this
--   migration is followed by one backfill and not by a gap in the record. That is
--   the one respect in which a rollup is safer than the table it aggregates.

-- ---------------------------------------------------------------------------
-- project_analytics_daily — one campaign, one day
-- ---------------------------------------------------------------------------
CREATE TABLE project_analytics_daily (
    project_id              uuid          NOT NULL REFERENCES projects (id) ON DELETE CASCADE,

    -- A `date`, not a timestamp: the row is about a calendar day and a timestamp
    -- would invite a reader to ask what time of day it means. Which calendar the
    -- day belongs to is the column below.
    day                     date          NOT NULL,

    -- The IANA zone the day boundaries were taken in, frozen onto the row. See
    -- the header: reconfiguring the platform zone must not silently re-label
    -- history that was bucketed under the old one.
    time_zone               text          NOT NULL,

    -- The campaign's currency, copied so that a reader never has to join to
    -- `projects` to know what the numbers are denominated in — and so that a
    -- campaign deleted and its currency changed cannot retroactively re-denominate
    -- a report.
    currency                text          NOT NULL,

    -- How many confirmed pledges landed on this day. Also the day's backer count:
    -- `pledges_project_backer_active_key` allows one active pledge per backer per
    -- campaign, so the two numbers are equal. Named for what is actually counted,
    -- because a column called `backer_count` would be a claim this table cannot
    -- make from a source that carries no backer identifier.
    pledge_count            bigint        NOT NULL,

    -- §7.3's numeric(14,2), as every other money column in this schema. Never a
    -- float: this is the figure a creator reads their campaign's day off.
    amount                  numeric(14,2) NOT NULL,

    -- The campaign's totals up to and including this day, so that a chart reads a
    -- running line without adding rows up — and so that a day with no pledges,
    -- which has no row at all, cannot make the line drop to zero.
    cumulative_pledge_count bigint        NOT NULL,
    cumulative_amount       numeric(14,2) NOT NULL,

    -- When this row was last computed. The freshness the read side returns, and
    -- the one signal that distinguishes "this campaign took nothing yesterday"
    -- from "the aggregator has not run since Tuesday".
    computed_at             timestamptz   NOT NULL,

    -- The grain, the conflict target of the upsert, and the index the dashboard
    -- reads by. One declaration doing all three is the point of the table.
    CONSTRAINT project_analytics_daily_pkey PRIMARY KEY (project_id, day),

    CONSTRAINT project_analytics_daily_currency_shape CHECK (currency ~ '^[A-Z]{3}$'),
    -- A row exists because something happened on that day. A zero row would be
    -- indistinguishable from a day the job has not reached, which is the one
    -- distinction the freshness column exists to preserve.
    CONSTRAINT project_analytics_daily_has_pledges CHECK (pledge_count > 0),
    CONSTRAINT project_analytics_daily_amount_is_positive CHECK (amount > 0),
    -- The running total includes this day, so it can never be behind it.
    CONSTRAINT project_analytics_daily_cumulative_covers_day CHECK (
        cumulative_pledge_count >= pledge_count AND cumulative_amount >= amount
    ),
    CONSTRAINT project_analytics_daily_time_zone_present CHECK (length(btrim(time_zone)) > 0)
);

COMMENT ON TABLE project_analytics_daily IS
    '§7.2 / #95: pre-aggregated daily metrics, one row per campaign per calendar day in the zone named by time_zone. Derived entirely from referral_attributions and safe to rebuild.';
COMMENT ON COLUMN project_analytics_daily.time_zone IS
    'The IANA zone whose midnights bound this day, frozen onto the row so that reconfiguring the platform zone cannot silently re-label history.';
COMMENT ON COLUMN project_analytics_daily.computed_at IS
    'When the rollup last wrote this row. Returned by the read side so that a stalled aggregator is visible to the dashboard rather than looking like a quiet week.';

-- ---------------------------------------------------------------------------
-- project_analytics_daily_channels — the same day, split by where it came from
-- ---------------------------------------------------------------------------
--
-- §4.7's CD-03 axis at the daily grain, and **the channel only**. The finer
-- labels a link can carry — `source`, `campaign`, `referrer_code` — are free text
-- that arrived in a URL, so their cardinality is bounded by what anybody chose to
-- put in one rather than by anything the platform controls; at a daily grain that
-- is an unbounded number of rows per campaign per day. `ReferralChannel` is a
-- closed set of seven, so a campaign's day is at most seven rows, and the full
-- source breakdown stays where it already works: `GET /referrers`, which folds
-- everything past a limit into one line at read time.
CREATE TABLE project_analytics_daily_channels (
    project_id   uuid          NOT NULL,
    day          date          NOT NULL,

    -- The closed vocabulary, mirrored by a check rather than a PostgreSQL enum
    -- type, exactly as V24 mirrors it — adding a channel should be a migration
    -- and not a deployment-ordering problem between a type and the code using it.
    channel      text          NOT NULL,

    pledge_count bigint        NOT NULL,
    amount       numeric(14,2) NOT NULL,

    CONSTRAINT project_analytics_daily_channels_pkey PRIMARY KEY (project_id, day, channel),

    -- To the parent row rather than to `projects` directly. It gives the cascade
    -- from a deleted campaign for free, and it also makes the stronger statement:
    -- a channel row for a day that has no daily row is a rollup that wrote half of
    -- itself, and the database refuses it instead of a dashboard rendering a split
    -- whose total is missing.
    CONSTRAINT project_analytics_daily_channels_day_fkey
        FOREIGN KEY (project_id, day) REFERENCES project_analytics_daily (project_id, day)
        ON DELETE CASCADE,

    CONSTRAINT project_analytics_daily_channels_channel_known CHECK (
        channel IN ('DIRECT', 'REFERRAL_LINK', 'SOCIAL', 'EMAIL', 'SEARCH', 'PAID', 'OTHER')
    ),
    CONSTRAINT project_analytics_daily_channels_has_pledges CHECK (pledge_count > 0),
    CONSTRAINT project_analytics_daily_channels_amount_is_positive CHECK (amount > 0)
);

COMMENT ON TABLE project_analytics_daily_channels IS
    '#95: one campaign-day split by ReferralChannel. The channel only, deliberately: the finer referral labels are unbounded free text and belong to the read-time report, not to a rollup.';

-- ---------------------------------------------------------------------------
-- What the rollup reads
-- ---------------------------------------------------------------------------

-- "Which campaigns took a pledge inside the re-rollup window." A range on
-- pledged_at across the whole table, which is the one query the job runs that is
-- not scoped to a campaign.
CREATE INDEX referral_attributions_pledged_at_idx
    ON referral_attributions (pledged_at);

-- "Every attribution for this campaign, in order." What the cumulative is
-- computed from — see the header for why it is recomputed rather than carried
-- forward — and a covering index, so the running total does not fetch the heap
-- once per pledge on a campaign with tens of thousands of them.
CREATE INDEX referral_attributions_rollup_idx
    ON referral_attributions (project_id, pledged_at)
    INCLUDE (amount, currency, channel);
