-- §7.2's trust-and-safety `reports` table and §4.11's AD-02 (#102): what somebody
-- who found prohibited content on the platform leaves behind, and what a moderator
-- works from.
--
-- ---------------------------------------------------------------------------
-- WHY THE TABLE IS CALLED `content_reports` AND NOT `reports`
-- ---------------------------------------------------------------------------
--
-- §7.2 names it `reports`, and this migration deliberately does not. The word
-- already means something else in three other places in the specification: CD-10
-- is "backer report with filtering and segmentation", PM-17 is "backer report with
-- segmentation and export", and §3.1's permission matrix has a row called "view the
-- backer report". None of those is a moderation object; they are exports a creator
-- downloads. A table called `reports` in a schema that will also grow a backer
-- report would be answered wrongly by the first support engineer who queried it,
-- and renaming it afterwards is a migration plus every query anybody wrote in the
-- meantime. §7.2 is updated to say so in the same pull request.
--
-- ---------------------------------------------------------------------------
-- WHAT A ROW IS
-- ---------------------------------------------------------------------------
--
-- One report, by one account, about one thing, with one outcome. Five facts, and
-- the reason each is a column rather than an implication:
--
--   * **the target** — what is being complained about, as a type and an identifier
--   * **the reporter** — who complained. Never null; see below
--   * **the reason** — §5.4's taxonomy, closed, because a moderator triaging a queue
--     sorts by it and free text cannot be sorted
--   * **the state** — OPEN until somebody decides, then terminal
--   * **the resolution** — who decided, when, and what they wrote
--
-- ---------------------------------------------------------------------------
-- NO FOREIGN KEY ON THE TARGET, AND WHY NOT
-- ---------------------------------------------------------------------------
--
-- `target_id` names rows in `projects` and `users` today, and in `comments` and
-- `project_updates` when §4.9's community module builds them. No single foreign key
-- can point at four tables — V19 makes the same argument about
-- `outbox_events.aggregate_id` and V21 about `audit_logs.entity_id`, and this table
-- is the third instance of the same shape rather than a new idea.
--
-- The consequence is stated rather than hidden: **a report can outlive what it was
-- about**, and that is the correct direction for this table. A campaign hard deleted
-- during an investigation must not take the record of the complaint with it, and a
-- report about a comment that has since been removed is precisely the evidence that
-- removing it was right.
--
-- `PROJECT_UPDATE` and `COMMENT` are in the check constraint although nothing can
-- write them yet: neither table exists, so §10.2's `POST /v1/comments/{id}/report`
-- has nothing to validate an identifier against and this release does not publish
-- it. They are enumerated now because adding a value to a CHECK is a migration and
-- a deployment-ordering problem, and because a queue that has to be taught a new
-- target type at the same moment the community module ships is a queue that ships
-- broken. The cost is one string in a constraint; the alternative is a second
-- migration on the critical path of somebody else's epic.
--
-- ---------------------------------------------------------------------------
-- THE REPORTER IS NEVER ANONYMOUS
-- ---------------------------------------------------------------------------
--
-- `reporter_id` is NOT NULL, and the endpoints behind it sit under
-- `SecurityConfiguration`'s catch-all rule, so a report requires a signed-in account
-- that is not inside §17.4's deletion grace period. Three things follow, and all
-- three are the point:
--
--   1. **Duplicates can be suppressed at all.** "The same reporter must not multiply
--      a report on the same target" is unstateable without an identity to compare,
--      and an unauthenticated form would make the open-report count — the only
--      triage signal this queue has — a number a single script chooses.
--   2. **The reporter is accountable.** A report is an accusation, and one that
--      cannot be traced back is one nobody can be stopped from making at scale.
--      `content_reports_reporter_idx` is what makes "what has this account reported"
--      cheap to ask.
--   3. **Abuse is bounded upstream.** Registration is already rate limited per
--      address, so requiring an account is itself most of the abuse control; the
--      per-reporter limit in `ModerationProperties` is the rest.
--
-- The reference has no ON DELETE clause, for `projects.creator_id`'s reason: §17.4
-- closes an account by anonymising the person in place, which leaves this row
-- intact and pointing at a `users` row that no longer names anybody.
--
-- ---------------------------------------------------------------------------
-- DUPLICATE SUPPRESSION IS A PARTIAL UNIQUE INDEX, NOT A SERVICE CHECK
-- ---------------------------------------------------------------------------
--
-- `content_reports_open_report_key` is unique over `(target_type, target_id,
-- reporter_id)` **where the report is still open**. Two decisions there.
--
-- It is in the database because a read-then-write check in Java loses the race
-- between two taps on a slow connection — both see no row, both insert, and the
-- campaign now carries two reports from one person, which inflates the only number
-- a moderator triages by. `ReminderRepository#insertIfAbsent` reaches the same
-- conclusion for the same reason, and the intake here is the same
-- `ON CONFLICT DO NOTHING`.
--
-- It is **partial on OPEN** rather than absolute because a resolved report is a
-- closed conversation. Somebody who reported a campaign in March, was told it was
-- dismissed, and finds the same campaign doing something worse in June is making a
-- new complaint about new facts, and an absolute unique index would silently drop
-- it — the worst possible failure for a safety feature, because the reporter is
-- shown a success. What the partial index refuses is the thing worth refusing:
-- piling up open reports faster than anybody can read them.
--
-- ---------------------------------------------------------------------------
-- PERSONAL DATA
-- ---------------------------------------------------------------------------
--
-- `detail` and `resolution_note` are free text written by a person, so both are
-- bounded in length and neither is indexed. What a reporter types is frequently
-- about somebody — that is what a report is — and this table is not a licence to
-- retain more of it than the complaint needs. There is no retention rule yet, which
-- is question "Personal data" in §22.1 and is unanswered; the bound is what keeps
-- the absence from being unbounded growth.
--
-- ---------------------------------------------------------------------------
-- Rolling deployment
-- ---------------------------------------------------------------------------
--
-- One new table, four new indexes, one trigger reusing V2's `set_updated_at`, and
-- nothing existing touched. No column is dropped, no constraint is added to an
-- existing table, and no previous release reads or writes anything here — the older
-- build does not know the table exists and the newer one is its only writer. Both
-- halves of a rolling deploy are safe in either order. This is an EXPAND with no
-- contract half.
--
-- Reverse:
--   DROP TABLE IF EXISTS content_reports;
--
--   The trigger and the four indexes go with the table. `set_updated_at` is V2's
--   and is shared, so it is deliberately left alone.
--
--   Safe while nothing writes here, which is true of every release before this one.
--   Afterwards it discards every outstanding complaint and every record of one
--   having been decided — including the reports a moderator upheld, whose only
--   other trace is the `audit_logs` row naming a report identifier that would then
--   point at nothing. There is no cheap way back from that, only a deliberate one.

CREATE TABLE content_reports (
    -- UUID v7, minted by the application like every other key here, so the queue
    -- can be paged by identifier and still be in the order the reports arrived.
    id              uuid        PRIMARY KEY,

    -- **What is being complained about.** A type and an identifier, no foreign
    -- key; see the header.
    target_type     text        NOT NULL,
    target_id       uuid        NOT NULL,

    -- **Who complained.** Never null; see the header for the three reasons.
    reporter_id     uuid        NOT NULL REFERENCES users (id),

    -- **Why**, from §5.4's list of what may not be on the platform, plus the two
    -- reasons that are not about the goods — FRAUD, which is AD-02's "fraud
    -- signals" and R6's fraudulent creator, and SPAM, which is what a comment
    -- report is mostly for.
    --
    -- A closed set held by a CHECK rather than open text, which is the opposite of
    -- the decision V19 and V21 make for `event_type` and `action`. The difference
    -- is who owns the set. An audit action is added by whichever feature adds a
    -- privileged action, so a new one must not be a migration; a report reason is a
    -- product and legal decision that appears in a dropdown, in four locales, and
    -- in a moderator's sort order — the queue has to be taught it either way, so
    -- there is nothing to be gained by letting the writing side invent one.
    reason          text        NOT NULL,

    -- What the reporter wrote. Optional, except for OTHER: a report that says
    -- "other" and nothing else cannot be acted on, and a queue full of them is a
    -- queue that gets ignored.
    detail          text,

    -- **OPEN until somebody decides, then terminal.** There is no third live state
    -- and deliberately no "under review": a claim flag is only worth having once
    -- more than one moderator works the queue at once, and until then it is a
    -- column that gets set and never cleared by whoever closed the tab.
    state           text        NOT NULL DEFAULT 'OPEN',

    -- **The resolution.** Who, when, and what they wrote. All three are null
    -- exactly while the report is open, which the constraints below state as an
    -- equivalence rather than trusting the application to keep them in step.
    --
    -- The note is not shown to the reporter by anything in this release. It is
    -- written for the next moderator to see the reasoning of the last one, which is
    -- the difference between a queue and a list of rows that keep coming back.
    resolved_by     uuid        REFERENCES users (id),
    resolved_at     timestamptz,
    resolution_note text,

    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT content_reports_target_type_known CHECK (
        target_type IN ('PROJECT', 'PROJECT_UPDATE', 'COMMENT', 'USER')
    ),
    CONSTRAINT content_reports_reason_known CHECK (
        reason IN (
            'PROHIBITED_ITEM',
            'MISREPRESENTATION',
            'NOT_ORIGINAL',
            'INTELLECTUAL_PROPERTY',
            'OFFENSIVE',
            'DISCRIMINATION',
            'SPAM',
            'FRAUD',
            'OTHER'
        )
    ),
    CONSTRAINT content_reports_state_known CHECK (state IN ('OPEN', 'UPHELD', 'DISMISSED')),

    -- Reporting yourself is not a complaint anybody has to read. Held here as well
    -- as in the service because the service check is enforced by whichever code
    -- path remembered to call it, and this one is enforced against a support
    -- script and a bulk import too.
    CONSTRAINT content_reports_reporter_is_not_the_target CHECK (
        target_type <> 'USER' OR target_id <> reporter_id
    ),

    -- OTHER says what. See the column comment: the alternative is an unactionable
    -- row, and an unactionable row in a safety queue is worse than no row, because
    -- it costs a moderator the same amount of attention.
    CONSTRAINT content_reports_other_says_what CHECK (
        reason <> 'OTHER' OR length(btrim(coalesce(detail, ''))) >= 1
    ),
    CONSTRAINT content_reports_detail_length CHECK (detail IS NULL OR length(detail) <= 2000),
    CONSTRAINT content_reports_resolution_note_length CHECK (
        resolution_note IS NULL OR length(resolution_note) <= 2000
    ),

    -- The three halves of a resolution arrive together or not at all. Stated as
    -- equivalences, in the voice of `reminders_notice_carries_its_way_out`: the
    -- incoherent states are "resolved by nobody at no time", which is a decision
    -- with no decider, and "open, and yet somebody already signed it off", which is
    -- a report that would be triaged again by the next person to read the queue.
    CONSTRAINT content_reports_resolution_names_its_moderator CHECK (
        (state = 'OPEN') = (resolved_by IS NULL)
    ),
    CONSTRAINT content_reports_resolution_is_dated CHECK (
        (state = 'OPEN') = (resolved_at IS NULL)
    ),
    CONSTRAINT content_reports_note_belongs_to_a_resolution CHECK (
        resolution_note IS NULL OR state <> 'OPEN'
    ),
    CONSTRAINT content_reports_resolution_follows_the_report CHECK (
        resolved_at IS NULL OR resolved_at >= created_at
    )
);

-- **Duplicate suppression.** Partial on OPEN; the header has the argument for both
-- halves of that sentence.
CREATE UNIQUE INDEX content_reports_open_report_key
    ON content_reports (target_type, target_id, reporter_id)
    WHERE state = 'OPEN';

-- The queue itself: everything in one state, oldest first, because a report that
-- has waited longest is the one that has waited longest. Paged by `id` rather than
-- by offset, and `id` is a UUID v7 (§7.3) so ordering by it is ordering by arrival
-- — which is why the index carries it rather than `created_at`.
CREATE INDEX content_reports_queue_idx ON content_reports (state, id);

-- "Everything ever reported about this campaign, this person." The other direction,
-- and the one a support conversation and the triage count both start from.
CREATE INDEX content_reports_target_idx ON content_reports (target_type, target_id, id DESC);

-- "What has this account reported." A reporter is accountable for their reports;
-- see the header. Without this, the question is a sequential scan and therefore a
-- question nobody asks.
CREATE INDEX content_reports_reporter_idx ON content_reports (reporter_id, id DESC);

CREATE TRIGGER content_reports_set_updated_at
    BEFORE UPDATE ON content_reports
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON TABLE content_reports IS
    'AD-02 (#102): §7.2''s trust-and-safety reports. One row per reporter per target while open; see V23 for why it is not called `reports`.';
COMMENT ON COLUMN content_reports.target_type IS
    'PROJECT, PROJECT_UPDATE, COMMENT or USER. The last two are enumerated ahead of the community module; nothing can write them yet.';
COMMENT ON COLUMN content_reports.state IS
    'OPEN, UPHELD or DISMISSED. Both resolutions are terminal: a decided report is re-opened by making a new report, not by editing this one.';
COMMENT ON COLUMN content_reports.resolution_note IS
    'Written for the next moderator, not for the reporter. Nothing in this release shows it to the person who made the report.';
