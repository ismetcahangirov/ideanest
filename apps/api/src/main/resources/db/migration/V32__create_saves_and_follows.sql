-- §4.9's C-09 and C-10 (#90): the two signals a backer leaves without spending
-- anything -- saving a campaign, and following the person running it.
--
-- ---------------------------------------------------------------------------
-- WHY TWO TABLES AND NOT ONE
-- ---------------------------------------------------------------------------
--
-- §7.2 lists `saves` and `follows` on one line, which reads as one shape with a
-- discriminator, and it is not. A save points at a campaign and a follow points
-- at a person: different referents, different cascade rules, different lifetime.
-- A single `signals (target_type, target_id)` table would have to drop both
-- foreign keys to hold either -- which is the deliberate trade V21 and V23 make
-- for `audit_logs` and `content_reports`, where the referent genuinely varies at
-- runtime and the row has to outlive what it was about.
--
-- Neither is true here. A save about a campaign that no longer exists is not
-- evidence of anything, and there are exactly two kinds, known now, that are not
-- going to become five. So both keys stay real and the database keeps enforcing
-- them.
--
-- ---------------------------------------------------------------------------
-- WITHDRAWAL IS A DELETE, AS IT IS FOR `reminders`
-- ---------------------------------------------------------------------------
--
-- A deliberate departure from §7.3, and V10 has already made the argument in
-- full for the third table of this family: soft delete exists for audit and for
-- recovery, and there is nothing to audit about somebody having un-saved a
-- campaign beyond no longer showing it to them. A `deleted_at` here would be a
-- record of what somebody used to be interested in, retained indefinitely,
-- which is exactly what §17.4 refuses -- and it would have to be filtered out of
-- every read and every count, forever, by everybody who ever writes one.
--
-- It also makes the unique constraints below say what they mean. A partial
-- unique index over `deleted_at IS NULL` is the shape that lets one person
-- accumulate a hundred tombstoned saves of the same campaign; a plain unique
-- constraint over two columns cannot be written around.
--
-- ---------------------------------------------------------------------------
-- NO COUNTER COLUMNS
-- ---------------------------------------------------------------------------
--
-- `projects` gains no `saves_count` and `users` gains no `followers_count`.
-- §8.4's `denormalization-sync` exists because some counters have to be cached,
-- and the test for whether one does is whether the count is on a hot read path
-- that cannot afford an index scan. Neither of these is, yet: a campaign card
-- does not show a save count today, and a creator page does not show a follower
-- count. Adding the column now means adding a second thing that can be wrong,
-- an hourly job to correct it, and a reconciliation nobody asked for. The
-- indexes below make both counts a cheap scan of a narrow index, which is the
-- right answer until a screen proves it is not.
--
-- ---------------------------------------------------------------------------
-- Reverse:
--   DROP TABLE IF EXISTS follows;
--   DROP TABLE IF EXISTS saves;
--   -- Reversing discards every save and every follow. Nothing references either
--   -- table, so the way back from a bad release is the previous build plus
--   -- these two lines. What it costs: the audiences of #245 go quiet rather
--   -- than wrong -- `SAVED_PROJECT_ENDING_SOON` and `FOLLOWED_CREATOR_LAUNCHED`
--   -- resolve to nobody -- and there is no way to reconstruct the rows, because
--   -- a save is a statement somebody made and not a fact derived from anything
--   -- else in the schema.
-- ---------------------------------------------------------------------------

CREATE TABLE saves (
    id          uuid        PRIMARY KEY,
    -- Cascades. A hard-deleted campaign is one that never launched (§7.3), and
    -- a save of a campaign that does not exist can never be shown to anybody.
    project_id  uuid        NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    -- No ON DELETE clause, for the reason `reminders.user_id` has none: §17.4
    -- closes an account by anonymising the person in place, which leaves this
    -- reference intact and pointing at a row that is no longer anybody. The
    -- audience read below then correctly resolves to an account the fan-out
    -- refuses to write a notification for.
    user_id     uuid        NOT NULL REFERENCES users (id),
    created_at  timestamptz NOT NULL DEFAULT now(),

    -- **Idempotency lives here, not in the service**, for V10's reason: a
    -- read-then-write check in Java loses the race between two taps on a slow
    -- connection -- both read no row, both insert -- and the campaign now shows
    -- two saves from one person. The endpoint inserts with ON CONFLICT DO
    -- NOTHING and lets the database decide, which it can only do if the rule is
    -- stated here.
    --
    -- A constraint rather than a unique index, unlike V10's: there is no
    -- nullable half of an exclusive-or to work around, so the plain form is
    -- available and it is the one that says what it means.
    CONSTRAINT saves_one_per_account UNIQUE (project_id, user_id)
);

-- "What have I saved", newest first -- §10.2's GET /v1/me/saved, keyset paged on
-- (created_at, id) exactly as the notification inbox is. DESC on both columns so
-- the index order is the page order and the read needs no sort.
CREATE INDEX saves_account_idx ON saves (user_id, created_at DESC, id DESC);

-- #245's `SAVERS`: everybody who saved this campaign, bounded and ordered by the
-- identifier so the answer is stable across a redelivered event. Covering, so
-- the audience read never touches the heap.
CREATE INDEX saves_project_idx ON saves (project_id, user_id);

COMMENT ON TABLE saves IS
    'C-09: a campaign somebody wants to come back to. One row per account per campaign; un-saving deletes it.';

CREATE TABLE follows (
    id           uuid        PRIMARY KEY,
    -- Who is being followed. No ON DELETE, for `saves.user_id`'s reason.
    --
    -- **Any account, not only one that has launched something.** Following is
    -- how somebody hears about a first campaign, so a constraint that required
    -- the target to already be a creator would refuse exactly the case the
    -- feature exists for: a pre-launch page shared before there is anything to
    -- back.
    creator_id   uuid        NOT NULL REFERENCES users (id),
    follower_id  uuid        NOT NULL REFERENCES users (id),
    created_at   timestamptz NOT NULL DEFAULT now(),

    -- Nobody follows themselves. Not tidiness: `FOLLOWED_CREATOR_LAUNCHED` is
    -- sent to a creator's followers when they launch, so a self-follow is a
    -- creator being told that they have launched their own campaign -- and it
    -- would arrive alongside the message they get for being the creator, from
    -- an audience the fan-out has no reason to deduplicate against.
    CONSTRAINT follows_is_not_self CHECK (creator_id <> follower_id),
    CONSTRAINT follows_one_per_pair UNIQUE (creator_id, follower_id)
);

-- "Who am I following", newest first. The mirror of `saves_account_idx` and for
-- the same read.
CREATE INDEX follows_follower_idx ON follows (follower_id, created_at DESC, id DESC);

-- #245's `FOLLOWERS`: everybody following this creator. The unique constraint
-- above already indexes (creator_id, follower_id) in that order, so this read is
-- served by it and no second index is created for it -- stated rather than left
-- to be rediscovered by whoever next wonders why the pair is asymmetric.

COMMENT ON TABLE follows IS
    'C-10: an account following another account. One row per pair; unfollowing deletes it. Never self-directed.';
COMMENT ON COLUMN follows.creator_id IS
    'Who is followed. Any account -- following is how somebody hears about a first campaign, so this is not restricted to accounts that have launched one.';
