-- §7.2's `comments` (#84): the conversation under a campaign. §4.4's Comments tab,
-- §4.9's C-01, C-02, C-03 and C-07.
--
-- ---------------------------------------------------------------------------
-- WHAT A ROW IS
-- ---------------------------------------------------------------------------
--
-- One thing one account said about one campaign, and — if it is a reply — the
-- comment it answers. §7.2 describes this table in three words, "self-referencing
-- threads", so the decisions below are this migration's rather than the
-- specification's, and each is argued where it is made.
--
-- ---------------------------------------------------------------------------
-- THE DEPTH IS BOUNDED AT ONE, AND THE DATABASE IS WHAT BOUNDS IT
-- ---------------------------------------------------------------------------
--
-- A campaign comment thread is two levels: somebody says something, and people
-- answer it. §4.4 calls the tab "chronological thread, creator replies
-- highlighted" and §10.2 gives it one reply route, `POST /v1/comments/{id}/reply`,
-- with no notion of replying to a reply. Unbounded nesting would buy nothing and
-- cost three things that matter here:
--
--   * **Reading.** An arbitrary tree is a recursive CTE per page, or a query per
--     level. Two levels is one keyset page of roots and one bounded query for
--     their replies, which is what `comments_roots_idx` and `comments_thread_idx`
--     below are shaped for. On a campaign with forty thousand comments that is the
--     difference between a tab that opens and one that times out.
--   * **Moderation.** CD-14 asks a creator to moderate comments. Removing a
--     comment eight levels down, on a screen that has to show the context, is a
--     product nobody has designed; removing a root and keeping its replies
--     readable is a rule that fits in a sentence — see the tombstone section.
--   * **Rendering.** Every client has to decide what depth 9 looks like on a phone.
--     The universal answer is to flatten it, which means the depth was never real.
--
-- So `depth` is 0 or 1 and nothing else. The application refuses a deeper reply
-- with a 422 naming the bound; this file refuses it as well, because an
-- application rule is enforced by whichever code path remembered to call it and a
-- constraint is enforced against a support script and a bulk import too.
--
-- **`comments_reply_hangs_below_its_parent` is what makes that airtight.** The
-- `depth` check alone would still accept a reply whose parent is itself a reply —
-- both rows would say depth 1, and the tree would quietly be three levels deep
-- with a column claiming otherwise. The composite foreign key states the real rule:
-- a row's parent is the row one level above it in the same thread. `parent_depth`
-- is generated rather than written, so the writing side cannot disagree with
-- `depth` about what "one level above" means, and a root's `parent_depth` of -1 is
-- never checked because MATCH SIMPLE satisfies a foreign key whose columns include
-- a null — which `parent_id` is, exactly for a root.
--
-- ---------------------------------------------------------------------------
-- WHY `thread_id` IS A COLUMN AND NOT A JOIN
-- ---------------------------------------------------------------------------
--
-- Every root carries its own identifier here, and every reply carries its root's.
-- It is denormalised and it is the whole of the read plan: one page of roots by
-- keyset, then **one** query for the replies of every root on that page —
-- `WHERE thread_id IN (...)` — rather than one query per root, which is the N+1
-- that makes a comments tab slow exactly on the campaign where it matters. It is
-- also what makes "this thread's replies, paged" a single index scan when a
-- popular thread outgrows the preview the list serves.
--
-- With a depth bound of one, `thread_id` could be derived as
-- `coalesce(parent_id, id)`. It is stored anyway because the composite foreign key
-- above carries it: that is what stops a reply from being attached to one comment
-- while claiming to belong to another comment's thread, and a derived expression
-- cannot be a foreign-key column.
--
-- ---------------------------------------------------------------------------
-- `by_creator` IS COMPUTED ON WRITE AND STORED, NOT DERIVED ON READ
-- ---------------------------------------------------------------------------
--
-- C-02 asks for "creator replies visually distinguished", and §4.4 puts the
-- highlight on the campaign page. Three ways to answer "did the campaign write
-- this", and only one of them is right:
--
--   * *The client says so.* No: the mark is a claim of authority on a page where
--     people are deciding whether to send money, and a claim the client makes is a
--     claim anybody can make.
--   * *Derived on read* — join `projects` and `collaborators` per row. It costs a
--     join on the hottest read, and it is **wrong**, which is the real objection: a
--     collaborator whose grant is revoked in March would silently lose the
--     highlight on everything they wrote in February, so the page would say the
--     campaign never answered when it had.
--   * *Decided at write time from the authorisation that was actually in force*, and
--     stored. That is a fact about the moment, it never moves, and it is one boolean
--     on a row the read already has. This column.
--
-- `false` is the default and the application always states it explicitly, so a row
-- inserted by a path that has not asked cannot accidentally claim the campaign said
-- something.
--
-- ---------------------------------------------------------------------------
-- DELETION IS A TOMBSTONE, ALWAYS, AND NEVER A DELETE
-- ---------------------------------------------------------------------------
--
-- `deleted_at` is set, the row stays, and the read serves it with no body and a
-- flag. Three reasons, in the order they bite:
--
--   1. **A deleted root must not orphan its replies.** Removing the row would take
--      every answer with it through `parent_id`'s cascade — a creator deleting their
--      own opening comment would delete the eleven backers who replied to it. The
--      answers are other people's speech and are not the deleter's to remove.
--   2. **A report outlives what it was about.** V23 says so at length and gives the
--      reason: "a report about a comment that has since been removed is precisely
--      the evidence that removing it was right". `content_reports.target_id` has no
--      foreign key, so a hard delete would leave a moderator holding an identifier
--      that resolves to nothing.
--   3. **A gap is not a record.** A thread where a comment simply vanishes reads as
--      though it was never said, and the replies quoting it become nonsense. A
--      tombstone lets the page say "this comment was removed", which is what
--      moderation transparency is.
--
-- A tombstone is served for a reply as well as for a root, deliberately, and for
-- the third reason above rather than the first.
--
-- **The body is kept on the row and never served.** It is what a moderator reading
-- a report about this comment has to look at, and blanking it on delete would let
-- somebody erase the evidence by deleting the thing complained about. It is
-- personal data written by a person about a person, so it is bounded in length and
-- not indexed, exactly as `content_reports.detail` is, and the retention question
-- is the same unanswered one — §22.1's "Personal data".
--
-- `deleted_by` is recorded because "the author withdrew this" and "the campaign
-- removed this" are different facts to everybody who reads the thread afterwards,
-- and because CD-14's removals are privileged actions with an `audit_logs` row that
-- has to be reconcilable against this table.
--
-- ---------------------------------------------------------------------------
-- WHO MAY WRITE ONE IS NOT DECIDED HERE
-- ---------------------------------------------------------------------------
--
-- §3.1 restricts commenting to "backers of that project and its creator". This
-- table cannot state that: a check constraint cannot see `pledges`, and a trigger
-- that could would put the comments tab behind a lock on the pledge ledger. It is
-- the application's, and `CommentService` says plainly which half of it this
-- release actually enforces and which half it cannot ask for yet.
--
-- ---------------------------------------------------------------------------
-- Rolling deployment
-- ---------------------------------------------------------------------------
--
-- One new table, three new indexes, one trigger reusing V2's `set_updated_at`, and
-- nothing existing altered. No column is dropped, no constraint is added to an
-- existing table, and no previous release reads or writes anything here — the older
-- build does not know the table exists and the newer one is its only writer. Both
-- halves of a rolling deploy are safe in either order, and the order they happen in
-- does not matter. This is an EXPAND with no contract half.
--
-- V23 already enumerates 'COMMENT' in `content_reports_target_type_known`, on
-- purpose and for exactly this migration, so making a comment reportable needs no
-- schema change at all — see that file's header.
--
-- Reverse:
--   DROP TABLE IF EXISTS comments;
--
--   The trigger and the three indexes go with the table. `set_updated_at` is V2's
--   and is shared, so it is deliberately left alone.
--
--   Safe while nothing writes here, which is true of every release before this one.
--   Afterwards it discards every conversation under every campaign, including the
--   creator's answers to questions their backers asked before they paid, and
--   including the comments that open reports in `content_reports` point at — those
--   rows survive and their `target_id` then names nothing. There is no cheap way
--   back from that. Take the previous build rather than this migration back, unless
--   the table is known to be empty.

CREATE TABLE comments (
    -- UUID v7, minted by the application like every other key here (§7.3), which is
    -- why the pages below are keyset by `id` and still in the order the comments
    -- arrived. No `created_at` in an index anywhere in this file for that reason.
    id           uuid        PRIMARY KEY,

    -- Cascades, as `project_updates` does. A campaign that can still be hard
    -- deleted is one that never launched, so nobody read the conversation under it.
    project_id   uuid        NOT NULL REFERENCES projects (id) ON DELETE CASCADE,

    -- The comment this one answers. NULL for a root, which is the same fact as
    -- `depth = 0` and is stated as an equivalence below rather than left to agree
    -- by habit.
    parent_id    uuid,

    -- The root of the conversation this row belongs to: its own id for a root. See
    -- the header for why this is a column and not `coalesce(parent_id, id)`.
    thread_id    uuid        NOT NULL,

    -- 0 for a root, 1 for a reply, and nothing else exists. See the header.
    depth        smallint    NOT NULL,

    -- Generated, never written. It is the second column of
    -- `comments_reply_hangs_below_its_parent`, and generating it is what stops the
    -- writing side from disagreeing with `depth` about what "one level up" means.
    -- -1 on a root, where the foreign key is not checked because `parent_id` is
    -- null and MATCH SIMPLE satisfies a partially null key.
    parent_depth smallint    GENERATED ALWAYS AS (depth - 1) STORED,

    -- Who said it. No ON DELETE clause, as on `project_updates.author_id`: §17.4
    -- anonymises a departing account in place, which leaves this reference valid and
    -- pointing at a row that no longer names anybody.
    author_id    uuid        NOT NULL REFERENCES users (id),

    -- What they said. Prose, escaped by the renderer — not `jsonb`, for
    -- `project_updates.body`'s reason and more so: nothing gives a comment a block
    -- editor, and an unvalidated document from an unvetted account on a public page
    -- is the specific thing §10.4 says not to store.
    body         text        NOT NULL,

    -- C-02's highlight. Decided at write time from the authorisation actually in
    -- force; see the header for why it is neither claimed by the client nor derived
    -- on read.
    by_creator   boolean     NOT NULL DEFAULT false,

    -- The tombstone. Both columns or neither; see the header for why the row stays
    -- and why the body stays on it.
    deleted_at   timestamptz,
    deleted_by   uuid        REFERENCES users (id),

    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),

    -- What `comments_reply_hangs_below_its_parent` points at. A unique key rather
    -- than an index for its own sake: `id` is already unique, so this adds no
    -- refusal — it exists because a foreign key needs a unique constraint over
    -- exactly the columns it references.
    CONSTRAINT comments_thread_level_key UNIQUE (id, depth, thread_id),

    -- **The threading rule, in the database.** A row's parent is the row one level
    -- above it, in the same thread. Combined with the depth bound, that is
    -- "replies attach to roots and to nothing else" with no way to write around it.
    CONSTRAINT comments_reply_hangs_below_its_parent
        FOREIGN KEY (parent_id, parent_depth, thread_id)
        REFERENCES comments (id, depth, thread_id)
        ON DELETE CASCADE,

    CONSTRAINT comments_depth_bounded CHECK (depth BETWEEN 0 AND 1),

    -- "A root is a comment with no parent" and "a root is a comment at depth 0" are
    -- one fact. Written as an equivalence, in the voice of
    -- `content_reports_resolution_names_its_moderator`, so the two cannot drift.
    CONSTRAINT comments_root_has_no_parent CHECK ((parent_id IS NULL) = (depth = 0)),

    -- A root heads its own thread; a reply never does. Two halves of the same
    -- statement, and the second is what stops a reply from claiming to be the root
    -- of a thread it is in.
    CONSTRAINT comments_root_heads_its_own_thread CHECK (depth <> 0 OR thread_id = id),
    CONSTRAINT comments_reply_does_not_head_a_thread CHECK (depth = 0 OR thread_id <> id),

    -- `!~ '^\s*$'` and not `char_length(btrim(...)) > 0`, for V22's reason:
    -- PostgreSQL's one-argument btrim removes spaces and nothing else, so a comment
    -- of two newlines passes it. The regexp class is what Java's String.isBlank
    -- means on the other side of the write.
    CONSTRAINT comments_body_not_blank CHECK (body !~ '^\s*$'),
    -- A bound on one row rather than an editorial opinion. Five thousand characters
    -- is far longer than anything anybody reads in a comment thread, and it is what
    -- stops one account from making a page expensive to store, serve and render.
    CONSTRAINT comments_body_length CHECK (char_length(body) <= 5000),

    -- A deletion has a time and a hand. "Deleted by nobody" and "deleted at no
    -- time" are both rows nobody can explain afterwards.
    CONSTRAINT comments_deletion_names_its_actor CHECK ((deleted_at IS NULL) = (deleted_by IS NULL)),
    CONSTRAINT comments_deletion_follows_the_comment CHECK (deleted_at IS NULL OR deleted_at >= created_at)
);

-- **The Comments tab.** One campaign's root comments, newest first, paged by `id` —
-- a UUID v7, so ordering by it is ordering by arrival and there is no `created_at`
-- to carry as a second column. Partial on roots because the list is a list of
-- conversations: including replies here would make the index the size of the table
-- to answer a query that never wants them.
CREATE INDEX comments_roots_idx
    ON comments (project_id, id DESC)
    WHERE parent_id IS NULL;

-- **The replies.** Ascending, because a conversation is read forwards even though
-- the list of conversations is newest first. Serves both readers: the one query
-- that fetches the replies of every root on a page (`thread_id IN (...)`), and the
-- keyset page of one popular thread's replies on their own.
CREATE INDEX comments_thread_idx
    ON comments (thread_id, id);

-- "Everything this account has written." What a moderator asks after upholding a
-- report about somebody, and what AD-04's ban is decided from. Without it the
-- question is a sequential scan of the largest table on the platform and therefore
-- a question nobody asks.
CREATE INDEX comments_author_idx
    ON comments (author_id, id DESC);

CREATE TRIGGER comments_set_updated_at
    BEFORE UPDATE ON comments
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON TABLE comments IS
    'C-01 to C-03 (#84): the conversation under a campaign. Two levels — a root and its replies — and deletion is a tombstone, never a row removal.';
COMMENT ON COLUMN comments.thread_id IS
    'The root of this conversation; the row''s own id for a root. Denormalised so one page of roots costs one further query for all of their replies.';
COMMENT ON COLUMN comments.depth IS
    '0 for a root, 1 for a reply. Bounded here as well as in the domain; comments_reply_hangs_below_its_parent is what makes the bound a real tree.';
COMMENT ON COLUMN comments.by_creator IS
    'C-02''s highlight. Decided at write time from the authorisation then in force, never claimed by the client and never derived on read.';
COMMENT ON COLUMN comments.deleted_at IS
    'A tombstone. The row and its body stay — replies must not be orphaned and a report must still resolve to something — and the read serves neither.';
