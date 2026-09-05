-- §5.5's monthly update, as a clock rather than as a clause. Issue #437.
--
-- ---------------------------------------------------------------------------
-- Reverse:
--   DROP TABLE IF EXISTS update_obligations;
--
--   Reversing discards every clock and every unresolved escalation. Note what it
--   costs, because it is not nothing: on the next sweep after re-applying, every
--   funded campaign opens a fresh obligation dated from that moment, so a
--   creator who has been silent for eight months starts again at zero and the
--   escalation a moderator was about to act on is gone. `audit_logs` keeps the
--   resolutions; it keeps nothing about the lapses that were never resolved.
--
--   Reverse this with the sweep stopped, and export first.
--
--   Safe under rolling deployment: the table is new and the sweep that reads it
--   ships with it. A release running without the sweep opens no obligations,
--   which is the fail-quiet direction -- nothing lapses that nobody is watching.
-- ---------------------------------------------------------------------------
--
-- ---------------------------------------------------------------------------
-- WHAT THIS IS FOR
-- ---------------------------------------------------------------------------
--
-- §5.5 lists four creator obligations, and the platform enforced none of them.
-- #426's agreement states them; an obligation that is stated and never checked
-- is a clause rather than a control.
--
-- This is the first of the four -- "publish an update at least monthly after a
-- successful campaign" -- as a clock that runs from the campaign's close until
-- fulfilment is complete.
--
-- ---------------------------------------------------------------------------
-- WHAT MAKES THE OBLIGATION REAL, AND WHAT DELIBERATELY DOES NOT
-- ---------------------------------------------------------------------------
--
-- Not a penalty. §22.3 already names the mechanism: "the creator's project
-- history visible". A creator whose last campaign went eight months without an
-- update, shown on the page where they are asking for money again, is a
-- consequence that costs them something and costs the platform nothing.
--
-- So this table produces two things and no third. It produces a **state** that
-- the campaign page and the creator's profile draw, and it produces an
-- **escalation** to a human queue. It produces no automatic refund and no
-- automatic suspension: §9.7 says a creator who cannot deliver "offers a refund;
-- the platform mediates", and suspension is a moderator decision under §4.11's
-- AD-02. An automatic one would be the platform adjudicating a dispute it has
-- told everybody it only mediates -- which contradicts the intermediary position
-- epic #421 exists to establish.
--
-- There is therefore no column here that any money or campaign state is derived
-- from, and that absence is the design rather than an omission.
--
-- ---------------------------------------------------------------------------
-- ONE ROW PER CAMPAIGN, NOT ONE PER CYCLE
-- ---------------------------------------------------------------------------
--
-- `deadline_notices` (V33) is the existing pattern for a clock that produces
-- notifications, and it is deliberately not copied wholesale. That table is a
-- set of claims -- one row per (campaign, threshold) -- because a deadline
-- notice happens twice ever and nothing needs to read "the current state of a
-- campaign's deadline notices".
--
-- This is the opposite shape. The campaign page asks "is this creator up to
-- date", once per render, and an answer assembled by aggregating a history of
-- claims is one every reader would assemble slightly differently. So the row is
-- the current state, and the two claims it has to make idempotently -- the
-- reminder, and the escalation -- are columns holding **the due date they were
-- made for** rather than booleans.
--
-- That is what makes "escalate exactly once rather than daily" expressible: the
-- sweep claims a cycle by writing `due_at` into `lapsed_for`, and `due_at` does
-- not move until an update moves it. A boolean would have needed clearing, and
-- the release that forgot to clear it is a campaign that never lapses again.

CREATE TABLE update_obligations (
    -- One obligation per campaign, and the campaign is the identity. CASCADE
    -- because an obligation is a fact about a campaign and outlives nothing.
    project_id uuid PRIMARY KEY REFERENCES projects (id) ON DELETE CASCADE,

    -- Denormalised from the campaign, for `fulfilments.project_id`'s reason: the
    -- read this table exists to serve is "every obligation this creator has",
    -- which the profile draws, and it would otherwise be a join to `projects` on
    -- every render.
    --
    -- CASCADE and not SET NULL: without a creator there is nobody the obligation
    -- is owed by, and a row that survived the erasure would be a lapse attached
    -- to nobody sitting in a moderator's queue forever.
    creator_id uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,

    -- The campaign's close. The clock starts here and not at the first update,
    -- so a creator who says nothing at all is already on the clock -- which is
    -- the case the whole mechanism is for.
    opened_at timestamptz NOT NULL,

    -- The most recent update the campaign has published since it closed. Null
    -- until there is one, which is a different fact from "updated at the close"
    -- and is what the screen says out loud: "no update since the campaign
    -- closed" rather than a date that looks like an update.
    last_update_at timestamptz,

    -- When the next update is owed. Maintained rather than computed, because it
    -- is what both the sweep's index and the two claims key off, and a computed
    -- expression would have to be repeated identically in three places.
    due_at timestamptz NOT NULL,

    CONSTRAINT update_obligations_due_after_opening CHECK (due_at > opened_at),

    -- The `due_at` a reminder was sent for. Not a boolean: see the header. Null
    -- means "no reminder for the current cycle", and it stops being equal to
    -- `due_at` the moment an update moves the due date, which is what re-arms
    -- the reminder for the next cycle without anything having to clear it.
    reminded_for timestamptz,

    -- The `due_at` that lapsed, claimed the same way and for the same reason.
    -- This is the column that makes the escalation happen once rather than every
    -- morning for the rest of the campaign's life.
    --
    -- Cleared when an update arrives, which re-arms the claim for the next cycle.
    lapsed_for timestamptz,

    -- When it lapsed. Set together with `lapsed_for` and **not cleared with it**.
    --
    -- The asymmetry is the point, and it is what makes this a queue rather than a
    -- snooze button. A creator who lapses, gets escalated, and then posts has
    -- brought their campaign up to date -- `due_at` moves and the public state
    -- goes back to CURRENT -- and has not made the escalation unhappen. Only a
    -- moderator closes a case, because the platform learning nothing about a
    -- creator who is repeatedly late is exactly the failure §22.3's visibility
    -- exists to prevent.
    lapsed_at timestamptz,

    -- A claim implies a time. A time does NOT imply a claim, because an update
    -- clears the claim and leaves the case open. Stated as an implication rather
    -- than an equality for that reason: the equality would refuse the row an
    -- ordinary sequence of events produces.
    CONSTRAINT update_obligations_lapse_has_a_time CHECK (
        lapsed_for IS NULL OR lapsed_at IS NOT NULL),

    -- A moderator looked at the escalation and closed it. **Closing an
    -- escalation is not closing the obligation**: the clock keeps running, and a
    -- creator who lapses again after being spoken to lapses again. What this
    -- records is that a human being has seen this one.
    resolved_at timestamptz,

    resolved_by uuid REFERENCES users (id) ON DELETE SET NULL,

    -- What the moderator decided, in their words. Required when there is a
    -- resolution, because the next person to open this campaign's file needs to
    -- know whether "resolved" meant "spoke to them" or "they had already posted"
    -- -- and a resolution nobody explained is one nobody can rely on.
    resolution_note text
        CONSTRAINT update_obligations_resolution_note_length CHECK (
            resolution_note IS NULL OR length(btrim(resolution_note)) BETWEEN 1 AND 2000),

    CONSTRAINT update_obligations_resolution_is_whole CHECK (
        (resolved_at IS NULL) = (resolution_note IS NULL)),

    -- A resolver implies a time. A time does not imply a resolver, because the
    -- resolver's account can be erased out from under it -- V58's asymmetry.
    CONSTRAINT update_obligations_resolution_has_a_time CHECK (
        resolved_by IS NULL OR resolved_at IS NOT NULL),

    -- Nothing is resolved that never lapsed. Without this, a resolution could be
    -- written against a campaign that is perfectly up to date, and the queue
    -- would be answerable by writing rows into it.
    CONSTRAINT update_obligations_resolution_needs_a_lapse CHECK (
        resolved_at IS NULL OR lapsed_at IS NOT NULL),

    -- Fulfilment is complete and the obligation is over. Null while it runs.
    --
    -- The clock stops here rather than at a fixed horizon, because §5.5's
    -- obligation is to keep backers informed until they have what they were
    -- promised, and a campaign that ships in three months and one that ships in
    -- two years owe the same thing for different lengths of time.
    closed_at timestamptz,

    created_at timestamptz NOT NULL DEFAULT now(),

    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TRIGGER update_obligations_set_updated_at
    BEFORE UPDATE ON update_obligations
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- **The sweep's question: which obligations are due something.**
--
-- Partial on the running ones, so that years of completed campaigns cost the
-- sweep nothing and so that "closed" is excluded by the index rather than by
-- somebody remembering to exclude it in each query.
CREATE INDEX update_obligations_due_idx
    ON update_obligations (due_at)
    WHERE closed_at IS NULL;

-- **The moderator's queue: lapses nobody has looked at, oldest first.**
--
-- Oldest first is the queue's whole ordering: the campaign that has been silent
-- longest is the one a backer is most likely to be asking about.
CREATE INDEX update_obligations_escalated_idx
    ON update_obligations (lapsed_at)
    WHERE lapsed_at IS NOT NULL AND resolved_at IS NULL;

-- **The profile's read: this creator's campaigns and how each is doing.**
--
-- §22.3's "the creator's project history visible", which #439 surfaces. Newest
-- first, because that is the order a profile lists campaigns in.
CREATE INDEX update_obligations_by_creator_idx
    ON update_obligations (creator_id, opened_at DESC);

COMMENT ON TABLE update_obligations IS
    'One campaign''s §5.5 monthly-update clock, from its close until fulfilment is complete (#437). Produces a visible state and a moderator escalation, and nothing automatic.';
COMMENT ON COLUMN update_obligations.lapsed_for IS
    'The due date this lapse was claimed for. A column and not a boolean, so the escalation happens once per cycle. Cleared by an update; lapsed_at is not.';
COMMENT ON COLUMN update_obligations.resolved_at IS
    'A moderator saw the escalation. Not the end of the obligation: the clock keeps running and a creator who lapses again lapses again.';
