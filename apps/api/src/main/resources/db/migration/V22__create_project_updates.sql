-- §7.2's `project_updates` (#83): the numbered posts a creator writes to the
-- people backing their campaign.
--
-- ---------------------------------------------------------------------------
-- WHAT THIS TABLE IS FOR
-- ---------------------------------------------------------------------------
--
-- §5.5 makes an update an obligation rather than a feature: "publish an update at
-- least monthly after a successful campaign", "inform backers of delays". §4.4
-- gives it a tab on the campaign page, §4.7's CD-12 gives the creator "publish
-- updates, public or backers-only, scheduled", and §10.2 gives it exactly two
-- endpoints — a public read and a creator write. This is the row behind all four.
--
-- ---------------------------------------------------------------------------
-- WHY THE NUMBER IS A COLUMN AND NOT A COUNT
-- ---------------------------------------------------------------------------
--
-- §4.4 says "numbered updates", and the number is what a person says out loud:
-- "update 7 said the moulds were late". That has to survive everything — a
-- campaign gaining an update, a backer bookmarking a link, a support conversation
-- six months later — so it is allocated once, stored, and never recomputed. A
-- number derived at read time as `row_number()` would renumber every earlier
-- update the first time one was withdrawn, and every link to update 7 would then
-- point at update 6.
--
-- Allocated by the application as max + 1 per campaign, exactly as
-- `project_story_versions.version_number` is, and the unique index below is what
-- decides a race between two writers rather than a lock nobody can see.
--
-- ---------------------------------------------------------------------------
-- SCHEDULING IS A TIMESTAMP, NOT A STATE MACHINE AND NOT A JOB
-- ---------------------------------------------------------------------------
--
-- CD-12 asks for scheduled updates. There is no `state` column here and no
-- `update-publisher` row in §8.4, and both absences are deliberate:
--
--   * **`published_at` in the future is the whole of "scheduled".** The public
--     read filters `published_at <= now()`, so an update becomes visible at the
--     instant it was scheduled for, with no sweep in between and therefore no
--     window in which a scheduled update is late because a job did not fire. A
--     `SCHEDULED -> PUBLISHED` column would be a second statement of the same
--     fact, and the two would disagree for exactly as long as the job was down.
--
--   * **A job would have to exist to be trusted.** §8.4 lists sixteen and this is
--     not one of them; adding one would put the visibility of a creator's post
--     behind a lease, a retry policy, and a `DEAD` state, in return for nothing
--     the timestamp does not already give.
--
-- What the timestamp cannot do is *send* anything at the scheduled moment. §4.10's
-- "new update published" notification is #85's, which does not exist yet — see the
-- note under §4.9. When it does, the thing that fans it out reads this column.
--
-- Because the number is allocated on insert and the public list is ordered by it,
-- `published_at` must not move backwards between consecutive updates, or update 6
-- would appear on the page a week after update 7. The application refuses that on
-- the write path; it is not a check constraint because a constraint cannot see the
-- previous row.
--
-- ---------------------------------------------------------------------------
-- WHY `body` IS text AND NOT jsonb
-- ---------------------------------------------------------------------------
--
-- `projects.story` is `jsonb` because §4.6 gives the story a block editor with
-- headings, images, and third-party embeds, and `StoryDocuments` is three hundred
-- lines of validation standing between a creator and the renderer. Nothing in the
-- specification gives an update that editor: §4.7's CD-12 asks for visibility and
-- scheduling and says nothing about blocks.
--
-- So this column holds prose, and the renderer escapes it. Storing `jsonb` today
-- would mean either duplicating the story's validator into this module — the
-- project module's `domain` package is not reachable from here, by
-- `ModuleBoundaryTests` — or accepting an unvalidated document on a public page,
-- which is the one thing §10.4 says not to do with creator content. When updates
-- do gain the block editor, the column becomes `jsonb` by an expand-then-contract
-- pair: add `body_document`, backfill each row as a single paragraph block, move
-- the readers, drop this one.
--
-- ---------------------------------------------------------------------------
-- NO SOFT DELETE COLUMN, YET
-- ---------------------------------------------------------------------------
--
-- §7.3 makes soft delete the platform's default and this table has no
-- `deleted_at`, because §10.2 gives an update no delete endpoint and AD-09's
-- content moderation of updates is not built. A nullable column nothing writes and
-- every read has to remember to filter on is a trap rather than a policy: the
-- first query that forgets it is the one that serves a withdrawn update. It
-- arrives with the endpoint that needs it, as one `ALTER TABLE` and one predicate.
--
-- Reverse:
--   DROP TABLE IF EXISTS project_updates;
--   -- Reversing loses every update every campaign has published, and unlike a
--   -- story version there is nowhere else the text still exists. Backers have
--   -- already read these; a rollback that drops them removes the record of what
--   -- a creator told the people who paid them. Take the previous build rather
--   -- than this migration back, unless the table is known to be empty.

CREATE TABLE project_updates (
    id           uuid        PRIMARY KEY,
    -- Cascades, as `project_story_versions` does. A campaign that can still be
    -- hard-deleted is one that never launched, so its updates were read by
    -- nobody and mean nothing without it.
    project_id   uuid        NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    -- Per campaign, from 1, allocated by the application as max + 1. See the
    -- header for why it is stored rather than derived.
    number       integer     NOT NULL,
    title        text        NOT NULL,
    -- Prose. See the header for why this is not jsonb.
    body         text        NOT NULL,
    -- CD-12's "public or backers-only". A text column with a check rather than a
    -- native enum, exactly as `projects.state` is: adding a third audience is
    -- then one migration rather than an ALTER TYPE that cannot run inside a
    -- transaction on every supported version.
    visibility   text        NOT NULL,
    -- Who wrote it. No ON DELETE clause, as on `project_story_versions`: §17.4
    -- anonymises a departing account in place, which leaves this reference valid
    -- and pointing at a row that no longer names anybody.
    author_id    uuid        NOT NULL REFERENCES users (id),
    -- When it becomes readable. In the future for a scheduled update; the public
    -- read compares it against now() and there is no state column to fall out of
    -- step with it.
    published_at timestamptz NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),

    -- Two rows claiming to be update 7 of the same campaign would make the page
    -- show whichever the planner found first. It is also what decides a race:
    -- two writers computing the same next number, and one of them refused rather
    -- than both stored. See ProjectUpdateService for what the loser is told.
    CONSTRAINT project_updates_number_key UNIQUE (project_id, number),
    CONSTRAINT project_updates_numbered_from_one CHECK (number >= 1),
    -- A title is what the tab lists and what a notification's subject line
    -- becomes; both bounds are the application's and are stated here as well,
    -- because an application check is enforced by whichever path remembered to
    -- call it and a constraint is enforced against a bulk import too.
    --
    -- `!~ '^\s*$'` and not `char_length(btrim(...)) > 0`. PostgreSQL's one-argument
    -- btrim removes *spaces* and nothing else, so a title of two newlines passes
    -- it — which is a check that looks like it refuses blank titles and does not.
    -- The regexp class covers every whitespace character, which is what Java's
    -- String.isBlank means on the other side of the write.
    CONSTRAINT project_updates_title_not_blank CHECK (title !~ '^\s*$'),
    CONSTRAINT project_updates_title_length CHECK (char_length(title) <= 120),
    -- An empty update is a notification sent about nothing. The ceiling is a
    -- bound on one row rather than an editorial opinion: forty thousand
    -- characters is longer than any update anybody reads to the end.
    CONSTRAINT project_updates_body_not_blank CHECK (body !~ '^\s*$'),
    CONSTRAINT project_updates_body_length CHECK (char_length(body) <= 40000),
    CONSTRAINT project_updates_visibility_known CHECK (visibility IN ('PUBLIC', 'BACKERS_ONLY'))
);

-- Every read of this table is "this campaign's updates, newest first", filtered
-- by whether they have been published yet. Descending so the list endpoint and
-- the next-number query both walk it in the direction they want.
CREATE INDEX project_updates_project_idx
    ON project_updates (project_id, number DESC);

COMMENT ON TABLE project_updates IS
    'Numbered posts a creator publishes to a campaign. Public or backers-only; a future published_at is a scheduled update.';
COMMENT ON COLUMN project_updates.number IS
    'Per campaign, from 1, allocated on insert and never renumbered — it is what a link and a support conversation name.';
COMMENT ON COLUMN project_updates.published_at IS
    'When the update becomes readable. In the future means scheduled; there is no state column and no job.';
