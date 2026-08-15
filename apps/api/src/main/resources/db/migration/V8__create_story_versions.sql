-- The campaign story's version history.
--
-- `projects.story` holds the document a creator is editing now. This table holds
-- the ones they were editing before, because the story is the single largest
-- piece of unrecoverable work in a campaign — §5.3 asks for at least five
-- hundred characters and a persuasive story is usually thousands — and the
-- editor autosaves it. Autosave without history means one accidental select-all
-- followed by a keystroke destroys an afternoon, silently, with a green "Saved"
-- beside it.
--
-- A separate table rather than a `previous_story` column: "the version before
-- this one" is not what a creator asks for. They ask for the one from before
-- they started rewriting the opening, which is three or four versions back, and
-- one column can only ever answer the first question.
--
-- WHY THIS IS NOT AN AUDIT TABLE. `project_state_transitions` is append-only and
-- never pruned, because a moderation decision has to be answerable to a
-- regulator years later. A story version is a convenience for the creator, so
-- this table is pruned to the most recent fifty per project by the application
-- (`ideanest.project.story.versions-kept`). Keeping every autosaved draft of
-- every campaign forever would be a table dominated by documents nobody will
-- ever open, and `jsonb` documents are not small.
--
-- Reverse:
--   DROP TABLE IF EXISTS project_story_versions;
--   -- Reversing loses every earlier draft of every story. The current story is
--   -- in `projects.story` and survives, so this is recoverable in the sense
--   -- that no campaign becomes unpublishable — but the history itself cannot be
--   -- reconstructed from anything else. The forward migration is safe to
--   -- reapply; the way back from a bad release is the previous build.

CREATE TABLE project_story_versions (
    id             uuid        PRIMARY KEY,
    -- Cascades, unlike the reference from `projects` to `users`. A campaign that
    -- is hard-deleted is one that never launched, and earlier drafts of its
    -- story have no meaning without the campaign they belong to.
    project_id     uuid        NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    -- Per project, starting at 1, allocated by the application as max + 1.
    -- Numbered rather than identified by timestamp because the number is what
    -- the creator is shown and what `POST .../story/versions/{number}/restore`
    -- names: "restore version 7" is a thing a person can say to support, and
    -- "restore the one from 14:32:07.481Z" is not.
    --
    -- The numbers are not renumbered when old versions are pruned. A number that
    -- moved would make a link somebody kept, or a support conversation, point at
    -- a different document.
    version_number int         NOT NULL,
    -- The whole document, in the shape of the epic contract §5 and validated by
    -- StoryDocuments before it is written. jsonb rather than text for the same
    -- reason as `projects.story`: it is queryable and indexable later without a
    -- rewrite, and Postgres refuses to store anything that is not JSON at all.
    document       jsonb       NOT NULL,
    -- Who was editing. No ON DELETE clause, as on `project_state_transitions`:
    -- §17.4 anonymises a departing account in place, which leaves this reference
    -- valid and pointing at a row that no longer names anybody. #38 makes this
    -- interesting — a collaborator's draft and the creator's are different
    -- things to look at in a list.
    author_id      uuid        NOT NULL REFERENCES users (id),
    created_at     timestamptz NOT NULL DEFAULT now(),

    -- Two rows claiming to be version 7 of the same story would make
    -- `.../story/versions/7` return whichever the planner found first. It is
    -- also the constraint that decides a race: two tabs of the same creator
    -- saving in the same instant both compute the same next number, and one of
    -- them is refused rather than both being stored. See StoryVersionService for
    -- why losing that race is harmless.
    CONSTRAINT project_story_versions_number_key UNIQUE (project_id, version_number),
    CONSTRAINT project_story_versions_numbered_from_one CHECK (version_number >= 1),
    -- The document is an object, never a bare array or a scalar. jsonb would
    -- accept `5` here, and a client reading it back expects the document of the
    -- contract; the failure belongs at the write.
    CONSTRAINT project_story_versions_document_is_an_object CHECK (jsonb_typeof(document) = 'object')
);

-- Every read of this table is "the history of this story, newest first" — the
-- list endpoint, the retention pass, and the next-number query. Descending in
-- the index so that all three walk it in the direction they want.
CREATE INDEX project_story_versions_project_idx
    ON project_story_versions (project_id, version_number DESC);

COMMENT ON TABLE project_story_versions IS
    'Earlier drafts of a campaign story. Pruned to the most recent fifty per project; not an audit table.';
COMMENT ON COLUMN project_story_versions.version_number IS
    'Per project, from 1, never reused and never renumbered when older versions are pruned.';
