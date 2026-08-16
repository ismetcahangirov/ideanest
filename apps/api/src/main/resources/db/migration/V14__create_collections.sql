-- Curated collections, open calls, and the editorial badge (#48). §4.3's
-- "Show only: editorially featured" and "Programmes: themed open calls", D-08,
-- D-05's badge on the card, §4.4's badge in the project header, and AD-03.
--
-- V6 left `is_featured` out of `projects` and said why: "curation is an
-- editorial workflow, not a boolean somebody sets by hand". This migration is
-- that workflow, and it deliberately does not add the column back. What a
-- reader sees as a badge is derived from membership of a collection a curator
-- put the campaign in — see `project_editorial_badges` at the bottom, which is
-- the whole of the derivation and the only definition of "editorially
-- featured" anywhere in the service.
--
-- Nothing here removes or narrows anything: four new tables, one new view, no
-- change to any existing column. Both halves of a rolling deployment are
-- therefore safe — the previous release does not know these tables exist, and
-- nothing outside this feature reads them.
--
-- Reverse:
--   DROP VIEW IF EXISTS project_editorial_badges;
--   DROP TABLE IF EXISTS curation_events;
--   DROP TABLE IF EXISTS collection_projects;
--   DROP TABLE IF EXISTS collection_translations;
--   DROP TABLE IF EXISTS collections;
--   Reversing destroys every editorial decision the platform has taken and the
--   append-only record of who took it, which is the one thing an audit trail
--   exists to survive. It also makes `showOnly=featured` and the programmes
--   filter unanswerable, so they would have to be refused again through
--   DiscoveryCapability — which is the previous build of the application. As
--   everywhere else in this directory, the way back from a bad release is that
--   build, and this block is here because it is genuinely the whole of the undo.

-- ---------------------------------------------------------------------------
-- collections
-- ---------------------------------------------------------------------------

-- One curated list. §4.3 and D-08 between them describe three things, and they
-- are three kinds of one table rather than three tables: all three have a slug
-- in a URL, a translated title and description, a publication decision, an
-- optional display window, cover imagery, an order, and an edited sequence of
-- campaigns behind them. The only thing that differs is what the list means to
-- a reader, which is exactly what a `kind` column is for.
CREATE TABLE collections (
    id                 uuid        PRIMARY KEY,
    -- The stable handle, and D-08's landing page URL: /collections/{slug}. Like
    -- every other slug in the schema it is what the outside world refers to the
    -- row by, which is why the identifier is generated rather than written down
    -- anywhere.
    slug               text        NOT NULL,
    kind               text        NOT NULL,
    -- The publication decision, and when it was taken. A nullable instant rather
    -- than a boolean, for the reason `projects.launched_at` is one: "published"
    -- and "published at" are the same fact, and a boolean beside a timestamp is
    -- two columns that can disagree.
    --
    -- Null is both "never published" and "withdrawn". Which of the two it is,
    -- and who decided, is in `curation_events` — the column says what is true
    -- now and the audit table says how it got there, exactly as `projects.state`
    -- and `project_state_transitions` divide the same work.
    published_at       timestamptz,
    -- The display window. An open call opens and closes; a seasonal theme
    -- expires. Both bounds are optional, because a staff selection is a standing
    -- list with neither.
    --
    -- Named for the open call rather than for the theme because that is the case
    -- with a real-world meaning: `opens_at` is when submissions to a programme
    -- are invited and `closes_at` is when they stop being. A seasonal collection
    -- reads them as the window it is shown in, which is the same predicate.
    opens_at           timestamptz,
    closes_at          timestamptz,
    -- WHETHER MEMBERSHIP OF THIS LIST BADGES ITS CAMPAIGNS. §3.2's "apply an
    -- editorial badge" is a moderator action, and this column is how that action
    -- is taken: a curator badges a campaign by putting it in a badge-granting
    -- collection, and un-badges it by taking it out. See the note above
    -- `project_editorial_badges` for why the badge is derived from membership
    -- rather than being a table of its own.
    --
    -- False by default, and it is not derived from `kind`, because a curator
    -- running a list — "Ending this week", "From last year's open call" — is a
    -- different act from putting the platform's name behind the campaigns in it.
    grants_badge       boolean     NOT NULL DEFAULT false,
    -- Placement, in the only sense §4.3 gives it any: the order collections are
    -- listed in by GET /v1/collections, and therefore the order a client renders
    -- them in. Deliberately NOT an enum of surfaces ("home", "discovery"): no
    -- section of the specification names those surfaces, and a vocabulary
    -- invented here would be one clients start depending on before anybody
    -- decided what it means.
    sort_order         int         NOT NULL DEFAULT 0,
    -- INTERIM, exactly as `projects.cover_image_*` is, with the same three
    -- columns, the same completeness constraint, and the same plan: when the
    -- media pipeline (§13) lands, these are replaced by a reference to `media`
    -- under expand-then-contract. Nothing outside the discovery module reads
    -- them, so that the contract half touches one module.
    cover_image_url    text,
    cover_image_width  int,
    cover_image_height int,
    -- Who created the list. No ON DELETE clause, for the reason
    -- `project_state_transitions.actor_id` has none: §17.4 anonymises a departing
    -- account in place, which leaves this pointing at a row that no longer names
    -- anybody, and a cascade would delete a published collection because the
    -- member of staff who made it left.
    created_by         uuid        REFERENCES users (id),
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),

    -- The three kinds §4.3 and D-08 describe, and nothing else. A text column
    -- with a check rather than a native enum, for the reason
    -- `projects_state_known` gives: adding a fourth kind is then an ordinary
    -- migration that runs inside a transaction, whereas ALTER TYPE ... ADD VALUE
    -- cannot be used by the same transaction that adds it — and an enum's
    -- declaration order silently becomes the sort order of every query that
    -- orders by kind.
    CONSTRAINT collections_kind_known CHECK (
        kind IN (
            'STAFF_SELECTION',  -- "Staff picks": what the platform stands behind
            'THEMED',           -- a season, a subject, an anniversary
            'OPEN_CALL'         -- §4.3's Programmes: a themed call with a window
        )
    ),
    -- The same slug shape as every other slug in the schema, so a collection can
    -- appear in a URL and in a filter without escaping.
    CONSTRAINT collections_slug_shape CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    CONSTRAINT collections_slug_length CHECK (length(slug) BETWEEN 2 AND 80),
    -- A window that closes before it opens is shown to nobody, ever, and is
    -- almost always two dates entered the wrong way round.
    CONSTRAINT collections_window_is_ordered CHECK (
        opens_at IS NULL OR closes_at IS NULL OR closes_at > opens_at
    ),
    CONSTRAINT collections_cover_image_is_complete CHECK (
        (cover_image_url IS NULL AND cover_image_width IS NULL AND cover_image_height IS NULL)
        OR (cover_image_url IS NOT NULL AND cover_image_width IS NOT NULL AND cover_image_height IS NOT NULL)
    ),
    CONSTRAINT collections_cover_image_has_extent CHECK (
        cover_image_width IS NULL OR (cover_image_width > 0 AND cover_image_height > 0)
    )
);

-- One collection per slug. Unique rather than merely indexed: two rows would
-- make /v1/collections/{slug} return whichever the planner found first, and the
-- programmes filter would count a campaign twice.
CREATE UNIQUE INDEX collections_slug_key ON collections (slug);

-- "Which collections may the public see, in what order" — the whole of
-- GET /v1/collections, and the base of every read below. Partial over the
-- published rows for the reason V12's indexes are partial over the nine visible
-- states: an unpublished collection is one the public read can never return, so
-- this is an index over the whole of what that endpoint can ever see rather than
-- an optimisation of a common case.
CREATE INDEX collections_published_idx
    ON collections (sort_order, slug)
    WHERE published_at IS NOT NULL;

CREATE TRIGGER collections_set_updated_at
    BEFORE UPDATE ON collections
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON TABLE collections IS
    'Staff selections, themed collections, and open calls (§4.3, D-08). Public visibility is '
    'published_at IS NOT NULL and now() inside [opens_at, closes_at).';
COMMENT ON COLUMN collections.grants_badge IS
    'Whether membership badges a campaign (§3.2, §4.4, D-05). The badge is derived; see project_editorial_badges.';
COMMENT ON COLUMN collections.cover_image_url IS
    'Interim, as projects.cover_image_url is. Replaced by a media reference when §13 lands.';

-- ---------------------------------------------------------------------------
-- collection_translations
-- ---------------------------------------------------------------------------

-- WHY A COLLECTION'S COPY IS TRANSLATED THE WAY THE TAXONOMY IS.
--
-- §21.1 says creator content is never machine-translated and is displayed in the
-- language it was written in. A collection's title and description are not
-- creator content: they are platform copy, written by staff, appearing in
-- navigation and as the heading and standfirst of D-08's landing page. That puts
-- them in the same category as a category name — interface text, which §21.1
-- makes key-based and translated — and so they follow V11 exactly: one row per
-- collection per locale, keyed by the pair, with the four codes of §21.1 in a
-- CHECK.
--
-- The alternative, a `title` and `description` on `collections` itself, has the
-- precise failure V11 was written to remove: shipping Russian would be a schema
-- change plus a deployment rather than an INSERT, and the platform ships four
-- languages.
--
-- No surrogate key, for the reason V11 gives on `category_translations`: the
-- identity of a translation is the thing it translates and the language it
-- translates it into, and a generated id would only give the table a second way
-- to say the same thing plus a way to write two rows for one pair.
CREATE TABLE collection_translations (
    collection_id uuid        NOT NULL REFERENCES collections (id) ON DELETE CASCADE,
    -- The four codes of §21.1 and nothing else, spelled as bare language codes
    -- rather than BCP 47 tags — the platform ships one Azerbaijani, not
    -- az-Latn-AZ, and a column that accepted both forms would eventually hold
    -- both for one language.
    locale        text        NOT NULL,
    title         text        NOT NULL,
    -- Optional. A staff selection is often a name and nothing else; an open call
    -- needs a paragraph saying what it is calling for. Null is "there is no
    -- standfirst", which a landing page renders by omitting it; empty is not a
    -- description and is almost always a client sending "" for "unset".
    description   text,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT collection_translations_pkey PRIMARY KEY (collection_id, locale),
    CONSTRAINT collection_translations_locale_known CHECK (
        locale IN (
            'az',  -- primary; every collection has one
            'en',  -- phase 1
            'ru',  -- phase 1
            'tr'   -- phase 3
        )
    ),
    -- Trimmed, so a title of nothing but spaces is not a title. Eighty, the same
    -- bound V11 puts on a taxon's name, because both are headings a reader scans
    -- rather than prose they read.
    CONSTRAINT collection_translations_title_length CHECK (length(btrim(title)) BETWEEN 1 AND 80),
    -- A landing-page standfirst, not the story of a campaign. Six hundred is
    -- roughly a long paragraph; past it this stops being a description and
    -- becomes content that wants its own editor.
    CONSTRAINT collection_translations_description_length CHECK (
        description IS NULL OR length(btrim(description)) BETWEEN 1 AND 600
    )
);

CREATE TRIGGER collection_translations_set_updated_at
    BEFORE UPDATE ON collection_translations
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON TABLE collection_translations IS
    'One title and description per collection per locale. Every collection has an az row; the '
    'fallback chain is the taxonomy''s — requested locale, then az, then the slug.';

-- WHY THE `az` RULE IS NOT A CONSTRAINT, AGAIN.
--
-- It is the same rule and the same three reasons V11 sets out at length: NOT NULL
-- constrains a column within a row, a partial unique index can forbid a second
-- Azerbaijani row but cannot require a first, and what is left is a deferred
-- constraint trigger firing on every insert and delete to enforce something that
-- is only violated by hand-editing.
--
-- So the invariant is held in three places here too, and one of them is stronger
-- than the taxonomy's: the write path refuses to create or update a collection
-- without an `az` title (CurationService), the public read falls back through
-- requested locale -> az -> slug (the same chain as Taxonomy.resolveName), and
-- CollectionApiTests asserts both. The taxonomy's `az` rows come from a seed and
-- are checked by a schema test; a collection's come from an admin request and are
-- checked by the request handler, which is the earlier and better place.

-- ---------------------------------------------------------------------------
-- collection_projects
-- ---------------------------------------------------------------------------

-- Membership, as an edited sequence rather than a set. A collection is a curator
-- saying "these, in this order": the first campaign on a staff-picks page is a
-- choice somebody made, and a membership table without a position would let the
-- planner make it instead.
CREATE TABLE collection_projects (
    -- Cascades. A collection that is deleted takes its membership with it, and a
    -- membership row pointing at a collection that does not exist is a campaign
    -- badged by nothing. (Deleting a collection is not something the admin API
    -- offers — see `curation_events` below, which is what prevents it once
    -- anything has happened to the list. The cascade is for the row that was
    -- created and removed before any of that.)
    collection_id uuid        NOT NULL REFERENCES collections (id) ON DELETE CASCADE,
    -- Cascades too. A hard-deleted campaign is one that never launched, and an
    -- edge to a campaign that does not exist is a badge on nothing and a
    -- programme count that is wrong. The audit row in `curation_events` does
    -- NOT cascade, which is the difference between the fact and the record of
    -- the decision — see there.
    project_id    uuid        NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    -- The curator's order. Sparse on purpose — the admin API writes 10, 20, 30 —
    -- so that inserting one campaign between two others is one UPDATE rather
    -- than a renumbering of the list.
    position      int         NOT NULL,
    -- Who put it here. No ON DELETE, for the reason `collections.created_by` has
    -- none: anonymisation leaves the reference valid, and a cascade would empty
    -- a published collection because a member of staff left.
    added_by      uuid        REFERENCES users (id),
    created_at    timestamptz NOT NULL DEFAULT now(),

    -- The pair is the identity: adding a campaign to the same collection twice
    -- is not a stronger editorial claim about it, and a duplicate row would show
    -- the card twice on the landing page and count it twice in the facet.
    CONSTRAINT collection_projects_pkey PRIMARY KEY (collection_id, project_id)
);

-- "Which campaigns are in this collection, in the curator's order" — the whole
-- of GET /v1/collections/{slug}, and the reverse of the primary key, which can
-- only answer "which collections is this campaign in".
--
-- `project_id` is in the index because it is in the ORDER BY: the keyset cursor
-- resumes after (position, project_id), and an index that stopped at `position`
-- would locate the start of a tie and then have to filter and re-sort the run of
-- rows sharing it.
--
-- NOT UNIQUE on (collection_id, position), deliberately. A unique position would
-- have to be DEFERRABLE INITIALLY DEFERRED for a reorder to be expressible as one
-- statement, and a deferred constraint reports its violation at COMMIT — a long
-- way from the statement that caused it. The tiebreaker makes the order total
-- whatever the positions are, which is the property the cursor actually needs.
CREATE INDEX collection_projects_order_idx
    ON collection_projects (collection_id, position, project_id);

-- "Which collections is this campaign in" — asked by `project_editorial_badges`
-- for every card in a feed, and by the project page header (§4.4).
CREATE INDEX collection_projects_project_idx
    ON collection_projects (project_id, collection_id);

COMMENT ON TABLE collection_projects IS
    'Curated membership, in the curator''s order. Position is sparse (10, 20, 30) so an insertion '
    'is one UPDATE.';

-- ---------------------------------------------------------------------------
-- curation_events
-- ---------------------------------------------------------------------------

-- Append-only. Never updated, never deleted.
--
-- §3.2 makes "apply an editorial badge" a moderator action and nobody else's,
-- and CLAUDE.md requires every privileged action to be audited. Putting a
-- campaign on the platform's own front page is a commercial act: it directs the
-- attention the platform has to the campaign somebody chose, and "who decided to
-- feature this, and why" is a question a creator whose campaign was passed over,
-- a journalist, and eventually a regulator all ask.
--
-- The shape is `project_state_transitions`': the row records who acted, what
-- they did, what they did it to, and the note they left, so the decision can be
-- reviewed by somebody who was not there. AD-14's platform-wide audit log is a
-- different table owned by epic #100; this is the module's own record, exactly
-- as the transitions table is the project module's.
CREATE TABLE curation_events (
    id            uuid        PRIMARY KEY,
    -- NO ON DELETE CLAUSE, and it is the reason a collection cannot be hard
    -- deleted once anything has been done to it. That is the intended
    -- consequence rather than an accident: an audit trail that a cascade can
    -- remove is an audit trail that disappears exactly when somebody wants it
    -- gone. Withdrawing a collection is unpublishing it, which writes a row here
    -- rather than removing one, and an unpublished collection 404s to the
    -- public.
    collection_id uuid        NOT NULL REFERENCES collections (id),
    -- Null for an event about the list itself — created, updated, published,
    -- unpublished — and set for an event about one campaign in it.
    --
    -- No ON DELETE clause either, so a campaign that has been curated cannot be
    -- hard deleted. Also intended: hard deletion is for a draft nobody has ever
    -- acted on, and staff putting a campaign in a public collection is somebody
    -- acting on it. `collection_projects` above cascades and this does not,
    -- which is the difference between the membership (a fact about now) and the
    -- decision that created it (a fact about then).
    project_id    uuid        REFERENCES projects (id),
    action        text        NOT NULL,
    -- No ON DELETE, for the reason project_state_transitions.actor_id has none:
    -- nulling it would be an update to a row this table promises never to
    -- update, and a cascade would delete the evidence of an editorial decision
    -- along with the account of the person who took it.
    actor_id      uuid        REFERENCES users (id),
    actor_role    text        NOT NULL,
    -- Why. §3.2's badge is a discretionary act and the note is the only place
    -- the reason for one survives; the admin API requires it on the actions that
    -- change what the public sees.
    note          text,
    created_at    timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT curation_events_action_known CHECK (
        action IN (
            'COLLECTION_CREATED',
            'COLLECTION_UPDATED',
            'COLLECTION_PUBLISHED',
            'COLLECTION_UNPUBLISHED',
            'PROJECT_ADDED',
            'PROJECT_REMOVED',
            'PROJECTS_REORDERED'
        )
    ),
    -- The two roles §3.2 grants the action to, plus the one a job would act
    -- under. Only MODERATOR is ever written today: the interim moderator
    -- directory is a list of addresses and cannot tell a moderator from an
    -- admin, and epic #100 brings the distinction along with the role model.
    -- ADMIN is declared now so that when it can be told apart, recording it is
    -- an INSERT rather than a migration.
    CONSTRAINT curation_events_actor_role_known CHECK (
        actor_role IN ('MODERATOR', 'ADMIN', 'SYSTEM')
    ),
    -- SYSTEM is the only role permitted to have no actor. A human decision
    -- recorded without saying whose it was is not an audit trail, and this is
    -- the constraint that stops one being written by a code path that forgot to
    -- pass the caller.
    CONSTRAINT curation_events_human_actions_name_the_actor CHECK (
        actor_id IS NOT NULL OR actor_role = 'SYSTEM'
    ),
    -- An event about a campaign names the campaign, and an event about the list
    -- does not name one. Without this, "PROJECT_REMOVED" with no project is a
    -- row that says a campaign was removed and cannot say which.
    CONSTRAINT curation_events_scope_matches_the_action CHECK (
        (action IN ('PROJECT_ADDED', 'PROJECT_REMOVED') AND project_id IS NOT NULL)
        OR (action NOT IN ('PROJECT_ADDED', 'PROJECT_REMOVED') AND project_id IS NULL)
    )
);

-- The history of one collection, in order. Every review of a curation decision
-- starts here.
CREATE INDEX curation_events_collection_idx ON curation_events (collection_id, created_at);
-- "Has this campaign ever been featured, and by whom" — the question a creator
-- asks. Partial, because most rows are about the list rather than about a
-- campaign and none of those can answer it.
CREATE INDEX curation_events_project_idx
    ON curation_events (project_id, created_at)
    WHERE project_id IS NOT NULL;
-- "What has this member of staff curated" — asked when a decision is disputed.
CREATE INDEX curation_events_actor_idx ON curation_events (actor_id, created_at);

COMMENT ON TABLE curation_events IS
    'Append-only audit of every curation decision (§3.2, CLAUDE.md). Never updated, never deleted.';

-- ---------------------------------------------------------------------------
-- project_editorial_badges
-- ---------------------------------------------------------------------------

-- WHY THE BADGE IS DERIVED FROM MEMBERSHIP RATHER THAN BEING ITS OWN TABLE.
--
-- The two options were a `project_editorial_badges` table keyed by project, and
-- this: a campaign is badged exactly when it is in a published, in-window
-- collection whose `grants_badge` is true.
--
-- The trade is real. A badge table lets a campaign be badged without belonging
-- to any list, which membership cannot express. Derivation was chosen anyway,
-- for three reasons that compound:
--
--   * There is one source of truth. With two, the answer to "is this campaign
--     featured" depends on which one a query reads, and the discovery filter,
--     the card, the project header, and #44's ranking are four readers that
--     would have to agree for ever.
--   * A badge with no list behind it is a badge with no reason behind it. §4.4
--     puts the badge in the project page header, where it has to say something
--     — "Staff pick", "Ramazan 2026" — and the thing it says is the collection's
--     translated title. A per-project flag would need its own label, its own
--     translations, and its own link target, which is a collection with the
--     word "collection" removed.
--   * V6 refused `is_featured` because "curation is an editorial workflow, not
--     a boolean somebody sets by hand". A badge table keyed by project is that
--     boolean with a timestamp on it: it can be set without curating anything,
--     which is exactly the act V6 declined to make possible.
--
-- What the trade costs is stated rather than hidden: to badge a campaign a
-- curator must put it in a list. The answer is that the staff-selection
-- collection is that list, and a platform that wants to feature a campaign
-- always has somewhere to feature it.
--
-- A VIEW rather than a materialised one. The predicate involves now(), the
-- underlying tables hold a handful of collections and their memberships, and a
-- materialised view would need a refresh path whose failure mode is a badge that
-- is silently a day old. #44 reads this, discovery's `showOnly=featured` reads
-- this, and §4.4's header will read this; three readers of one definition is the
-- whole point.
--
-- HOW #44 CONSUMES IT for §11.2's `w4 × editorial bonus`: join or EXISTS against
-- this view by `project_id`. It carries one row per badge, so `count(*)` is a
-- campaign in more than one badge-granting list, and `granted_at` is when the
-- membership was created — enough for a bonus that decays, if the weight turns
-- out to want one. Nothing about the ranking is decided here.
CREATE VIEW project_editorial_badges AS
SELECT cp.project_id,
       c.id   AS collection_id,
       c.slug AS collection_slug,
       c.kind AS collection_kind,
       cp.created_at AS granted_at
  FROM collection_projects cp
  JOIN collections c ON c.id = cp.collection_id
 WHERE c.grants_badge
   AND c.published_at IS NOT NULL
   -- The same window predicate the public read applies, written here once so
   -- that a badge cannot outlive the collection that grants it. A campaign in
   -- last spring's expired themed collection is not featured today.
   AND (c.opens_at IS NULL OR c.opens_at <= now())
   AND (c.closes_at IS NULL OR c.closes_at > now());

COMMENT ON VIEW project_editorial_badges IS
    'The only definition of "editorially featured" (§3.2, §4.4, D-05). One row per campaign per '
    'badge-granting collection currently in force. #44 reads it for §11.2''s w4.';

-- No seed. A collection is an editorial decision, and a migration that invented
-- one would put fictional staff picks on the front page of every environment
-- including production. The admin API under /v1/admin/collections is how the
-- first one is created.
