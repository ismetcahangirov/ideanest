-- §4.4's FAQ tab (#283): the question and answer list a creator maintains on
-- their campaign.
--
-- ---------------------------------------------------------------------------
-- Reverse:
--   DROP TABLE IF EXISTS project_faqs;
--   ALTER TABLE collaborator_capabilities
--       DROP CONSTRAINT collaborator_capabilities_known;
--   ALTER TABLE collaborator_capabilities
--       ADD CONSTRAINT collaborator_capabilities_known CHECK (
--           capability IN (
--               'EDIT_BASICS', 'EDIT_REWARDS', 'EDIT_STORY', 'SUBMIT_FOR_REVIEW',
--               'PUBLISH_UPDATES', 'RESPOND_TO_COMMENTS', 'VIEW_FINANCES',
--               'MANAGE_COLLABORATORS'));
--
--   Dropping the table loses every question a creator has answered on every
--   campaign, and unlike a story version there is nowhere else the text still
--   exists: an FAQ entry is written once, in the editor, and is never copied to
--   `project_story_versions` or anywhere else. Backers have read these before
--   deciding to pledge, and several of them are the promise a support
--   conversation later turns on. Take the previous build back rather than this
--   migration, unless the table is known to be empty.
--
--   Narrowing the capability list back is the half that can *fail*, and the
--   failure is the interesting part: it refuses if any collaborator has been
--   granted 'MANAGE_FAQ', which is exactly the row this migration made
--   grantable. That is a real grant a creator issued and not a corruption, so
--   whoever reverses this has to decide whether to delete those rows first —
--   and deleting them silently withdraws an authority somebody was given.
-- ---------------------------------------------------------------------------
--
-- ---------------------------------------------------------------------------
-- WHAT THIS TABLE IS FOR
-- ---------------------------------------------------------------------------
--
-- §4.4's tab table gives the campaign page an FAQ tab and describes it in five
-- words: "Creator-managed question and answer list". §4.6 puts an "FAQ editor"
-- in the campaign editor's story tab, §4.7's CD-15 is "FAQ management", §7.2
-- lists a `faqs` table as "question and answer pairs", and §10.2 gives it a
-- public read and a creator write. #283 was blocked because none of that
-- existed anywhere in the schema. This is the row behind all five.
--
-- It lives in the community module beside `project_updates` and `comments`
-- rather than in the project module, for the reason those two do: it is
-- creator-authored campaign *content* addressed to the people reading the page,
-- not part of what §5.3 freezes when a campaign launches.
--
-- ---------------------------------------------------------------------------
-- WHY A TABLE AND NOT A jsonb COLUMN ON projects
-- ---------------------------------------------------------------------------
--
-- The cheap alternative is `projects.faqs jsonb` holding an array of pairs, and
-- it is cheap for exactly as long as nobody edits one. Three things make it the
-- wrong shape:
--
--   * **§10.2 addresses a single entry.** `PATCH /v1/faqs/{id}` and
--     `DELETE /v1/faqs/{id}` are in the endpoint list, so an entry needs an
--     identifier that survives its neighbours being reordered and deleted. An
--     array index is not one: it changes when the entry above it is removed, so
--     a client holding a stale index edits somebody else's question.
--
--   * **Two writers.** A jsonb array is read, mutated and written whole, so a
--     creator and a collaborator with the FAQ capability editing two different
--     questions in two tabs produce one surviving edit and no error. Rows make
--     that two `UPDATE` statements that do not touch each other.
--
--   * **`projects` is the hottest row on the platform.** Every campaign page,
--     every discovery card and every pledge reads it. Growing it by an
--     unbounded document that only one tab of one page ever displays makes
--     every one of those reads carry the FAQ, and TOAST does not help a
--     `SELECT *`.
--
-- The counter-argument is `projects.story`, which *is* jsonb. That column holds
-- one document with one editor and no addressable parts, and §4.6 gives it a
-- block editor with headings, images and embeds — a shape a table cannot hold
-- without inventing a block table. An FAQ list is the opposite: many small
-- rows, each addressed, each edited alone.
--
-- ---------------------------------------------------------------------------
-- WHY sort_order AND NOT A LINKED LIST
-- ---------------------------------------------------------------------------
--
-- §4.4 gives the creator the order — the list is theirs to arrange, most-asked
-- first — so the order has to be stored. The two candidates are an integer
-- position per row and a `previous_faq_id` pointer per row, and the pointer
-- loses on every count that matters:
--
--   * Reading the list in order becomes a recursive CTE walking one row at a
--     time, instead of `ORDER BY sort_order` served by the index below.
--   * A single broken or duplicated pointer silently truncates the list or
--     loops it, and no constraint expressible on one row catches either.
--   * A drag-and-drop reorder of n entries is n pointer rewrites in a
--     particular sequence, so an interrupted one leaves a list nobody asked
--     for. With positions the whole list is rewritten from zero, so two
--     concurrent reorders produce one of the two orders rather than a blend —
--     which is exactly what `RewardService.reorder` already does for tiers.
--
-- Named `sort_order` and not `display_order`, matching `reward_tiers.sort_order`
-- (V7), which is the other creator-arranged list on a campaign and the one this
-- reorder endpoint is modelled on. One name for one idea.
--
-- **Deliberately not unique per campaign.** A reorder rewrites every row in the
-- campaign, and a unique constraint would refuse the intermediate states of
-- that rewrite unless it were deferred — the same reasoning, and the same
-- comment, as `reward_tiers.sort_order`. Ties are broken by `created_at`, so a
-- list that has never been reordered is in the order it was written.
--
-- ---------------------------------------------------------------------------
-- WHY question AND answer ARE text AND NOT jsonb
-- ---------------------------------------------------------------------------
--
-- `projects.story` is jsonb because §4.6 gives the story a block editor and
-- `StoryDocuments` is three hundred lines of validation standing between a
-- creator and the renderer. Nothing in the specification gives an FAQ entry that
-- editor: §4.4 says "question and answer list" and §7.2 says "question and
-- answer pairs".
--
-- So these hold prose and the renderer escapes them. Storing jsonb today would
-- mean either duplicating the story's validator into this module — the project
-- module's `domain` package is not reachable from here, by `ModuleBoundaryTests`
-- — or serving an unvalidated document on a public page. Both are the trade
-- `project_updates.body` already refused. If an answer ever needs a link or a
-- list, it becomes jsonb by an expand-then-contract pair: add `answer_document`,
-- backfill each row as a single paragraph block, move the readers, drop this
-- one.
--
-- ---------------------------------------------------------------------------
-- WHY A NEW CAPABILITY AND NOT EDIT_BASICS
-- ---------------------------------------------------------------------------
--
-- The second half of this migration adds 'MANAGE_FAQ' to
-- `collaborator_capabilities_known`. The alternative was to let the FAQ
-- endpoints ask for EDIT_BASICS, and `ProjectAccess` already records what
-- happens when a published surface offers a coarse check: the analytics module
-- found the only question it could reach, asked it, and the referral report
-- became readable by anybody granted any editing capability. A coarse escape
-- hatch on a published surface will be taken.
--
-- An FAQ entry is also a different kind of authority from a campaign's basics.
-- It is text published in the campaign's name to everybody reading the page —
-- nearer to PUBLISH_UPDATES than to the funding goal — and a creator who wants
-- somebody to answer questions without repricing the campaign has no way to say
-- so unless the grant exists. That is the same sentence §16.1 uses to explain
-- why the vocabulary was published at all.
--
-- The names are the wire format: this constraint, `project.domain.Capability`,
-- `shared.access.ProjectCapability` and the People tab all spell it
-- 'MANAGE_FAQ', and renaming it later is a breaking change to a client and to
-- every stored row.
--
-- ---------------------------------------------------------------------------
-- ROLLING DEPLOYMENT
-- ---------------------------------------------------------------------------
--
-- One new table nothing in the previous release reads or writes, and one CHECK
-- constraint replaced by a strictly wider one. Both halves are safe with both
-- versions of the code serving traffic: the previous release never writes
-- 'MANAGE_FAQ', so the wider constraint cannot refuse a row it could have
-- written, and every existing row satisfies the new constraint because the new
-- value set is a superset of the old one. This is an EXPAND.
--
-- Contract: none, and the `DROP CONSTRAINT` below is not one. Nothing is
-- removed — no table, no column, no value that any row currently holds — and no
-- reader loses anything, so there is no later release that has to finish this
-- change. A CHECK constraint cannot be widened in place, which is why the
-- statement is written as a drop and an add; the pair is the smallest way to
-- express "accept one more spelling". It is two statements in one migration and
-- therefore one transaction, so no request ever sees the table unconstrained.
-- ---------------------------------------------------------------------------

CREATE TABLE project_faqs (
    id         uuid        PRIMARY KEY,
    -- Cascades, as `project_updates` and `project_story_versions` do. A campaign
    -- that can still be hard-deleted is one that never launched, so nobody read
    -- its FAQ and the answers mean nothing without the questions' campaign.
    project_id uuid        NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    -- What a backer asked, as the creator chose to phrase it. Not attributed to
    -- anybody: §4.4 calls this list "creator-managed", so the question is the
    -- creator's summary of what people keep asking rather than a quotation of
    -- one person — which is also why there is no author_id on this table and
    -- there is one on project_updates.
    question   text        NOT NULL,
    -- The answer, as prose. See the header for why this is not jsonb.
    answer     text        NOT NULL,
    -- The creator's order, rewritten from zero by the reorder endpoint. Not
    -- unique: see the header.
    sort_order int         NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    -- Maintained by the trigger below rather than by the application, so an
    -- entry cannot claim to have been edited at a time the application chose.
    updated_at timestamptz NOT NULL DEFAULT now(),

    -- Both bounds are the application's, in `FaqContent`, and are stated here as
    -- well — an application check is enforced by whichever path remembered to
    -- call it, and a constraint is enforced against a bulk import and against
    -- the next write path somebody adds.
    --
    -- `!~ '^\s*$'` and not `char_length(btrim(...)) > 0`. PostgreSQL's
    -- one-argument btrim removes *spaces* and nothing else, so a question of two
    -- newlines passes it — a check that looks like it refuses blank questions
    -- and does not. The regexp class covers every whitespace character, which is
    -- what Java's String.isBlank means on the other side of the write.
    CONSTRAINT project_faqs_question_not_blank CHECK (question !~ '^\s*$'),
    -- A question is one sentence somebody would type into a support form. Long
    -- enough for "will you ship to Georgia before the new year, and how much
    -- does it cost", short enough that the tab is a list of questions rather
    -- than a list of paragraphs.
    CONSTRAINT project_faqs_question_length CHECK (char_length(question) <= 200),
    -- An entry with no answer is a question the page asks and does not answer,
    -- which is worse for a backer than the question's absence.
    CONSTRAINT project_faqs_answer_not_blank CHECK (answer !~ '^\s*$'),
    -- A bound on one row rather than an editorial opinion. Four thousand
    -- characters is longer than any answer anybody reads to the end, and it is
    -- what stops one entry from making the whole unpaged list expensive to
    -- serve.
    CONSTRAINT project_faqs_answer_length CHECK (char_length(answer) <= 4000),
    -- Positions are rewritten from zero, so a negative one is a bug rather than
    -- a choice. Bounded above as well because the column is what an ORDER BY
    -- reads and an overflowing position would silently sort an entry to the
    -- wrong end.
    CONSTRAINT project_faqs_sort_order_is_a_position CHECK (sort_order BETWEEN 0 AND 10000)
);

-- Every read of this table is "this campaign's FAQ, in the creator's order" —
-- the public tab, the editor, and the reorder endpoint's own load. One index
-- serves all three.
CREATE INDEX project_faqs_project_sort_idx
    ON project_faqs (project_id, sort_order);

CREATE TRIGGER project_faqs_set_updated_at
    BEFORE UPDATE ON project_faqs
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- The capability half. See WHY A NEW CAPABILITY, above, and the note on
-- reversing it.
ALTER TABLE collaborator_capabilities
    DROP CONSTRAINT collaborator_capabilities_known;

ALTER TABLE collaborator_capabilities
    -- The nine capabilities of the campaign editor's People tab, and nothing
    -- else. A capability nobody implemented is a grant that silently authorises
    -- nothing, which reads to a creator as the platform ignoring them.
    ADD CONSTRAINT collaborator_capabilities_known CHECK (
        capability IN (
            'EDIT_BASICS',           -- title, summary, category, goal, duration
            'EDIT_REWARDS',          -- items, tiers, shipping
            'EDIT_STORY',            -- the story document and the risks section
            'SUBMIT_FOR_REVIEW',     -- send the campaign to moderation
            'PUBLISH_UPDATES',       -- post updates to backers
            'RESPOND_TO_COMMENTS',   -- reply as the campaign
            'MANAGE_FAQ',            -- §4.4's FAQ tab (#283): write, edit, reorder
            'VIEW_FINANCES',         -- the backer report and the money in it
            'MANAGE_COLLABORATORS'   -- invite and revoke; only a creator may grant this
        )
    );

COMMENT ON TABLE project_faqs IS
    '§4.4''s FAQ tab (#283): creator-managed question and answer pairs on a campaign, in the creator''s own order.';
COMMENT ON COLUMN project_faqs.question IS
    'The creator''s phrasing of what people keep asking (#283). Not attributed: §4.4 calls this list creator-managed, which is why there is no author on this table.';
COMMENT ON COLUMN project_faqs.answer IS
    'Prose, not a document (#283). See V47 for why this is not jsonb and what the expand-then-contract pair would be if it became one.';
COMMENT ON COLUMN project_faqs.sort_order IS
    'The creator''s order, rewritten from zero by a reorder so two concurrent ones produce one of the two orders rather than a blend (#283).';
