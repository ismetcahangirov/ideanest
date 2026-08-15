-- Campaigns: the taxonomy they are filed under, the row itself, and the
-- append-only record of every state it has been in.
--
-- The state column and `project_state_transitions` are one design, not two
-- tables that happen to be created together. §6.1 gives sixteen states and a
-- fixed set of edges between them; the column says where a project is now and
-- the transition table says how it got there. A funding platform is asked "who
-- approved this, and when did it go live" by creators, by moderators, and
-- eventually by a regulator, and a single mutable column cannot answer any of
-- those questions.
--
-- Reverse:
--   DROP TABLE IF EXISTS project_state_transitions;
--   DROP TABLE IF EXISTS projects;
--   DROP TABLE IF EXISTS subcategories;
--   DROP TABLE IF EXISTS categories;
--   -- Reversing destroys every campaign and the audit trail behind it. On a
--   -- database that has served traffic this is not a rollback, it is data loss:
--   -- the forward migration is safe to reapply, and the way back from a bad
--   -- release is the previous build of the application, not this block.

-- ---------------------------------------------------------------------------
-- categories and subcategories
-- ---------------------------------------------------------------------------

-- The minimum `projects.category_id` needs to point at something. Curation,
-- imagery, and the facet counts discovery draws from belong to epic #42; what
-- is here is a two-level taxonomy with both names, because §11.3 requires the
-- Azerbaijani and English titles to exist side by side rather than being
-- resolved through a translation table nobody has built yet.
CREATE TABLE categories (
    id         uuid        PRIMARY KEY,
    -- The stable handle. Everything outside the database — a URL, a fixture, a
    -- seed script — refers to a category by slug, never by identifier, which is
    -- why the identifiers below are generated rather than written down.
    slug       text        NOT NULL,
    name_az    text        NOT NULL,
    name_en    text        NOT NULL,
    -- Display order, decided by the platform rather than alphabetically: an
    -- alphabetical list in Azerbaijani and the same list in English are two
    -- different orders, and the navigation would reshuffle with the language.
    sort_order int         NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT categories_slug_shape CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    CONSTRAINT categories_slug_length CHECK (length(slug) BETWEEN 2 AND 60),
    CONSTRAINT categories_name_az_not_blank CHECK (length(btrim(name_az)) > 0),
    CONSTRAINT categories_name_en_not_blank CHECK (length(btrim(name_en)) > 0)
);

CREATE UNIQUE INDEX categories_slug_key ON categories (slug);

CREATE TRIGGER categories_set_updated_at
    BEFORE UPDATE ON categories
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON TABLE categories IS
    'Top-level project taxonomy. Referred to by slug everywhere outside the database.';

CREATE TABLE subcategories (
    id         uuid        PRIMARY KEY,
    -- Named parent_id rather than category_id so that the column says which
    -- direction the hierarchy runs even when the row is read on its own.
    parent_id  uuid        NOT NULL REFERENCES categories (id) ON DELETE CASCADE,
    slug       text        NOT NULL,
    name_az    text        NOT NULL,
    name_en    text        NOT NULL,
    sort_order int         NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT subcategories_slug_shape CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    CONSTRAINT subcategories_slug_length CHECK (length(slug) BETWEEN 2 AND 60),
    CONSTRAINT subcategories_name_az_not_blank CHECK (length(btrim(name_az)) > 0),
    CONSTRAINT subcategories_name_en_not_blank CHECK (length(btrim(name_en)) > 0),
    -- Lets `projects` name a category and a subcategory in one foreign key.
    -- Without it the pair can disagree; see projects_subcategory_belongs_to_category.
    CONSTRAINT subcategories_identity_within_parent UNIQUE (id, parent_id)
);

-- Unique within a parent, not globally: the path is /discover/{category}/{sub},
-- so two parents may both have an "other" and neither is ambiguous. A global
-- unique index would force names like "games-other", which then leak into URLs.
CREATE UNIQUE INDEX subcategories_slug_key ON subcategories (parent_id, slug);

CREATE TRIGGER subcategories_set_updated_at
    BEFORE UPDATE ON subcategories
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON TABLE subcategories IS
    'Second level of the taxonomy. Slugs are unique within a parent, not globally.';

-- ---------------------------------------------------------------------------
-- projects
-- ---------------------------------------------------------------------------

-- §7.2, minus the columns that belong to features which do not exist yet.
--
-- Deliberately absent, each arriving with the feature that owns it rather than
-- as a column nothing writes to:
--
--   * `location_id` and `geo_point` — proximity search, epic #42. A geography
--     column also means PostGIS, and enabling an extension for a feature nobody
--     has built is a migration that can only be justified twice.
--   * `search_vector` — #43. It is a generated tsvector over title, blurb, and
--     story, and its definition is the ranking decision that issue exists to
--     make; adding it now would mean rewriting it there.
--   * `main_image_id` and `video_id` — the media pipeline (§13). See the note
--     on the cover image columns below.
--   * `is_featured` — #48. Curation is an editorial workflow, not a boolean
--     somebody sets by hand.
--   * `pledge_manager_state` — epic #72, which is a state machine of its own and
--     will bring its own audit table.
--
-- `items`, `reward_tiers`, `shipping_rules`, and `collaborators` are separate
-- migrations owned by #32 and #38.
CREATE TABLE projects (
    id                  uuid           PRIMARY KEY,
    -- No ON DELETE clause on purpose: a campaign is a financial record and must
    -- outlive an account that closes. §17.4 anonymises a departing user in
    -- place, which leaves this reference intact and pointing at a row that no
    -- longer names anybody. A cascade here would delete other people's pledges
    -- along with the campaign they were made to.
    creator_id          uuid           NOT NULL REFERENCES users (id),
    -- Unique per creator, not globally: the public URL is
    -- /projects/{creatorSlug}/{projectSlug} (§10.2), so two creators may both
    -- launch a "coffee-table-book" without either having to be renamed.
    slug                text           NOT NULL,
    title               text           NOT NULL,
    -- Null while the campaign is a draft. §5.3 requires both by submission, and
    -- the checklist (#37) is what refuses to submit without them — a NOT NULL
    -- here would instead refuse to create the draft the editor starts from.
    blurb               text,
    category_id         uuid           REFERENCES categories (id),
    subcategory_id      uuid,
    state               text           NOT NULL DEFAULT 'DRAFT',
    goal_amount         numeric(14, 2),
    -- The currency the goal, every pledge, and the payout are denominated in.
    -- Fixed at creation; changing it after a pledge exists would reprice
    -- somebody's commitment.
    currency            text           NOT NULL DEFAULT 'AZN',
    -- Denormalised from the pledge ledger, which stays the source of truth.
    -- Maintained by the pledge module (epic #50) and read here so that a
    -- discovery page showing a hundred progress bars is one query.
    pledged_amount      numeric(14, 2) NOT NULL DEFAULT 0,
    backers_count       int            NOT NULL DEFAULT 0,
    duration_days       int,
    -- What the creator asked for, before approval. `launched_at` is what
    -- actually happened; keeping both is how "launched two days late" stays
    -- answerable.
    scheduled_launch_at timestamptz,
    launched_at         timestamptz,
    -- Computed from launched_at plus duration_days at launch, and stored rather
    -- than derived: §5.3 freezes the deadline once the campaign is live, and a
    -- derived value would move if duration_days were ever edited.
    deadline            timestamptz,
    -- The rich-text document, shaped by #35 and validated there. Left opaque
    -- here so that autosave can store a draft document before the validator
    -- exists, and jsonb rather than text so that the story can be queried and
    -- indexed later without a rewrite.
    story               jsonb,
    risks               text,
    -- INTERIM. §5.3 makes a cover image of at least 1024x576 a submission
    -- requirement, and a completeness checklist cannot check a column that does
    -- not exist -- so the image lives here as three plain columns rather than
    -- waiting for the media module. There is no `media` table, no uploader, and
    -- no transcoding state yet (§13).
    --
    -- When the media pipeline lands it replaces these with `main_image_id`
    -- referencing `media`, under expand-then-contract: that release adds the
    -- reference and backfills it from these columns while both are written, and
    -- a later release drops these three. Nothing outside the project module may
    -- read them, so that the contract half touches one module.
    cover_image_url     text,
    cover_image_width   int,
    cover_image_height  int,
    late_pledge_enabled boolean        NOT NULL DEFAULT false,
    late_pledge_ends_at timestamptz,
    created_at          timestamptz    NOT NULL DEFAULT now(),
    updated_at          timestamptz    NOT NULL DEFAULT now(),

    -- The sixteen states of §6.1 and nothing else. A text column with a check
    -- rather than a native enum type: adding or renaming a state is then an
    -- ordinary migration that runs inside a transaction, whereas ALTER TYPE ...
    -- ADD VALUE cannot be used by the same transaction that adds it, and the
    -- type's declaration order silently becomes the sort order of every query
    -- that orders by state.
    CONSTRAINT projects_state_known CHECK (
        state IN (
            'DRAFT',              -- being written; visible to its creator only
            'PRELAUNCH',          -- a public teaser page collecting followers
            'SUBMITTED',          -- waiting in the moderation queue
            'CHANGES_REQUESTED',  -- returned to the creator with a note
            'REJECTED',           -- terminal: refused by moderation
            'APPROVED',           -- cleared, waiting for the creator to launch
            'SCHEDULED',          -- cleared, with a launch date set
            'LIVE',               -- taking pledges
            'SUSPENDED',          -- terminal: stopped by trust and safety
            'CANCELED',           -- terminal: stopped by the creator
            'SUCCESSFUL',         -- deadline passed at or above goal
            'UNSUCCESSFUL',       -- terminal: deadline passed below goal
            'COLLECTING',         -- charging the confirmed pledges
            'LATE_PLEDGE',        -- collected, still accepting late pledges
            'FULFILLING',         -- collected, rewards being delivered
            'COMPLETED'           -- terminal: everything delivered
        )
    ),
    CONSTRAINT projects_slug_shape CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    CONSTRAINT projects_slug_length CHECK (length(slug) BETWEEN 1 AND 80),
    -- §5.3: 1-60 characters. Trimmed, so a title of nothing but spaces is not a
    -- title. Both bounds are also checked in Java, because a constraint
    -- violation reaches the client as a 500 and a validated field as a 400.
    CONSTRAINT projects_title_length CHECK (length(btrim(title)) BETWEEN 1 AND 60),
    -- §5.3: 1-135. Null is a draft that has not written one yet; empty is not a
    -- summary and is almost always a client sending "" for "unset".
    CONSTRAINT projects_blurb_length CHECK (blurb IS NULL OR length(btrim(blurb)) BETWEEN 1 AND 135),
    CONSTRAINT projects_currency_shape CHECK (currency ~ '^[A-Z]{3}$'),
    -- A goal of zero is met before it is announced, and a negative one has no
    -- meaning at all.
    CONSTRAINT projects_goal_is_positive CHECK (goal_amount IS NULL OR goal_amount > 0),
    CONSTRAINT projects_pledged_is_not_negative CHECK (pledged_amount >= 0),
    CONSTRAINT projects_backers_is_not_negative CHECK (backers_count >= 0),
    -- §5.3: 1-60 days.
    CONSTRAINT projects_duration_in_range CHECK (duration_days IS NULL OR duration_days BETWEEN 1 AND 60),
    -- A subcategory without a category is half a filing, and the pair has to
    -- agree: "Tabletop games" under "Technology" would put the campaign in a
    -- facet nobody browsing that category expects. The composite reference is
    -- only checked when both columns are present, which is what the check above
    -- it makes safe.
    CONSTRAINT projects_subcategory_needs_a_category CHECK (
        subcategory_id IS NULL OR category_id IS NOT NULL
    ),
    CONSTRAINT projects_subcategory_belongs_to_category
        FOREIGN KEY (subcategory_id, category_id) REFERENCES subcategories (id, parent_id),
    -- Three columns describing one image. Two of the three set means an upload
    -- half recorded, and the checklist would then read "cover image present"
    -- from a row it cannot render.
    CONSTRAINT projects_cover_image_is_complete CHECK (
        (cover_image_url IS NULL AND cover_image_width IS NULL AND cover_image_height IS NULL)
        OR (cover_image_url IS NOT NULL AND cover_image_width IS NOT NULL AND cover_image_height IS NOT NULL)
    ),
    CONSTRAINT projects_cover_image_has_extent CHECK (
        cover_image_width IS NULL OR (cover_image_width > 0 AND cover_image_height > 0)
    ),
    -- A deadline that precedes the launch would make every "is this campaign
    -- still open" comparison in the platform answer no.
    CONSTRAINT projects_deadline_follows_launch CHECK (
        deadline IS NULL OR launched_at IS NULL OR deadline > launched_at
    ),
    CONSTRAINT projects_late_pledge_window_needs_the_feature CHECK (
        late_pledge_ends_at IS NULL OR late_pledge_enabled
    ),
    -- Everything from LIVE onwards is a campaign the public has seen and may
    -- have pledged to, and all-or-nothing (§5.1) is decided by comparing
    -- pledged_amount against goal_amount at deadline. A row in one of those
    -- states with either missing cannot be resolved at all, so the transition
    -- into LIVE is the last moment at which it can still be refused.
    CONSTRAINT projects_public_states_are_fully_specified CHECK (
        state NOT IN (
            'LIVE', 'SUSPENDED', 'CANCELED', 'SUCCESSFUL', 'UNSUCCESSFUL',
            'COLLECTING', 'LATE_PLEDGE', 'FULFILLING', 'COMPLETED'
        )
        OR (
            goal_amount IS NOT NULL
            AND duration_days IS NOT NULL
            AND launched_at IS NOT NULL
            AND deadline IS NOT NULL
        )
    )
);

-- One campaign per creator per slug, which is what makes the public URL
-- resolvable. Unique rather than merely indexed: two rows would make
-- /projects/{creatorSlug}/{projectSlug} return whichever the planner found.
CREATE UNIQUE INDEX projects_creator_slug_key ON projects (creator_id, slug);
-- The scheduler's query: which live campaigns have reached their deadline. Also
-- serves "campaigns ending soon", which is the default discovery sort.
CREATE INDEX projects_state_deadline_idx ON projects (state, deadline);
-- Browsing a category shows live campaigns and nothing else.
CREATE INDEX projects_category_state_idx ON projects (category_id, state);
-- The creator dashboard, and the authorisation check on every editor request.
CREATE INDEX projects_creator_idx ON projects (creator_id);

CREATE TRIGGER projects_set_updated_at
    BEFORE UPDATE ON projects
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON TABLE projects IS
    'A campaign. `state` moves only through ProjectTransitionService, which records the move.';
COMMENT ON COLUMN projects.cover_image_url IS
    'Interim. Replaced by main_image_id when the media module lands; see V6 for the plan.';
COMMENT ON COLUMN projects.pledged_amount IS
    'Denormalised from the pledge ledger, which remains the source of truth.';

-- ---------------------------------------------------------------------------
-- project_state_transitions
-- ---------------------------------------------------------------------------

-- Append-only. Never updated, never deleted.
--
-- CLAUDE.md requires every privileged action to be audited, and moderation
-- decisions are the clearest case of one: approving a campaign lets it collect
-- money from the public. The row records who decided, what they decided, and
-- the note the creator was shown, so that the decision can be reviewed by
-- somebody who was not there.
CREATE TABLE project_state_transitions (
    id         uuid        PRIMARY KEY,
    -- Cascades, unlike the reference from projects to users. A campaign that is
    -- hard-deleted is one that never launched -- nothing else may point at it
    -- by then -- and its transition history has no meaning without it.
    project_id uuid        NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    -- Null exactly once per project, on creation: there is no state before
    -- DRAFT. Recording creation as a transition rather than inferring it from
    -- created_at means the history is complete on its own terms.
    from_state text,
    to_state   text        NOT NULL,
    -- No ON DELETE clause, again: nulling this would be an update to a row this
    -- table promises never to update, and a cascade would delete the evidence
    -- of a moderation decision along with the moderator's account. Deletion of
    -- a person is anonymisation (§17.4), which leaves this reference valid and
    -- pointing at a row that no longer identifies them.
    actor_id   uuid        REFERENCES users (id),
    actor_role text        NOT NULL,
    -- The moderator's reason, or the creator's cancellation reason. Shown to
    -- the creator, so it is written for them and not for a log.
    note       text,
    created_at timestamptz NOT NULL DEFAULT now(),

    -- The same sixteen states as projects.state. Repeated rather than shared
    -- through a domain type, for the reason given on that constraint; a
    -- transition to a state that does not exist is a bug worth refusing at the
    -- point it would be written.
    CONSTRAINT project_state_transitions_states_known CHECK (
        to_state IN (
            'DRAFT', 'PRELAUNCH', 'SUBMITTED', 'CHANGES_REQUESTED', 'REJECTED',
            'APPROVED', 'SCHEDULED', 'LIVE', 'SUSPENDED', 'CANCELED',
            'SUCCESSFUL', 'UNSUCCESSFUL', 'COLLECTING', 'LATE_PLEDGE',
            'FULFILLING', 'COMPLETED'
        )
        AND (
            from_state IS NULL
            OR from_state IN (
                'DRAFT', 'PRELAUNCH', 'SUBMITTED', 'CHANGES_REQUESTED', 'REJECTED',
                'APPROVED', 'SCHEDULED', 'LIVE', 'SUSPENDED', 'CANCELED',
                'SUCCESSFUL', 'UNSUCCESSFUL', 'COLLECTING', 'LATE_PLEDGE',
                'FULFILLING', 'COMPLETED'
            )
        )
    ),
    -- A transition from a state to itself is not a transition. Recording one
    -- would make the history impossible to read as a sequence of changes.
    CONSTRAINT project_state_transitions_change_something CHECK (from_state IS NULL OR from_state <> to_state),
    CONSTRAINT project_state_transitions_actor_role_known CHECK (
        actor_role IN (
            'CREATOR',       -- the account that owns the campaign
            'COLLABORATOR',  -- someone the creator granted a capability to (#38)
            'MODERATOR',     -- platform staff acting through the admin API
            'SYSTEM'         -- a scheduled job: deadline reached, launch due
        )
    ),
    -- SYSTEM is the only role permitted to have no actor. A human decision
    -- recorded without saying whose it was is not an audit trail, and this is
    -- the constraint that stops one being written by a code path that forgot to
    -- pass the caller.
    CONSTRAINT project_state_transitions_human_actions_name_the_actor CHECK (
        actor_id IS NOT NULL OR actor_role = 'SYSTEM'
    )
);

-- The history of one campaign, in order. Every read of this table is that
-- question.
CREATE INDEX project_state_transitions_project_idx
    ON project_state_transitions (project_id, created_at);
-- "What has this moderator decided" -- asked when a decision is disputed.
-- Partial, because the creator's own transitions are the bulk of the table and
-- are never the subject of that question.
CREATE INDEX project_state_transitions_moderator_idx
    ON project_state_transitions (actor_id, created_at)
    WHERE actor_role = 'MODERATOR';

COMMENT ON TABLE project_state_transitions IS
    'Append-only audit of every project state change. Never updated, never deleted.';

-- ---------------------------------------------------------------------------
-- Seed taxonomy
-- ---------------------------------------------------------------------------

-- Enough of a taxonomy for the editor to have something to select and for
-- discovery to have facets to count. Identifiers are generated rather than
-- written as literals: a literal in a migration is the value a fixture or a
-- client eventually hardcodes, and the stable handle for a category is its
-- slug. Names follow §11.3 -- Azerbaijani first, because that is the primary
-- language of the platform.
INSERT INTO categories (id, slug, name_az, name_en, sort_order) VALUES
    (gen_random_uuid(), 'technology',  'Texnologiya',     'Technology',   10),
    (gen_random_uuid(), 'design',      'Dizayn',          'Design',       20),
    (gen_random_uuid(), 'games',       'Oyunlar',         'Games',        30),
    (gen_random_uuid(), 'art',         'İncəsənət',       'Art',          40),
    (gen_random_uuid(), 'music',       'Musiqi',          'Music',        50),
    (gen_random_uuid(), 'film',        'Film və video',   'Film & video', 60),
    (gen_random_uuid(), 'publishing',  'Nəşriyyat',       'Publishing',   70),
    (gen_random_uuid(), 'food',        'Qida və içki',    'Food & drink', 80),
    (gen_random_uuid(), 'fashion',     'Moda',            'Fashion',      90),
    (gen_random_uuid(), 'community',   'İcma',            'Community',   100);

-- Selected by parent slug rather than by identifier, for the same reason.
INSERT INTO subcategories (id, parent_id, slug, name_az, name_en, sort_order)
SELECT gen_random_uuid(), categories.id, seed.slug, seed.name_az, seed.name_en, seed.sort_order
FROM categories
JOIN (VALUES
    ('technology', 'hardware',      'Avadanlıq',        'Hardware',            10),
    ('technology', 'software',      'Proqram təminatı', 'Software',            20),
    ('technology', 'robotics',      'Robototexnika',    'Robotics',            30),
    ('design',     'product',       'Məhsul dizaynı',   'Product design',      10),
    ('design',     'graphic',       'Qrafik dizayn',    'Graphic design',      20),
    ('games',      'tabletop',      'Stolüstü oyunlar', 'Tabletop games',      10),
    ('games',      'video',         'Video oyunlar',    'Video games',         20),
    ('art',        'illustration',  'İllüstrasiya',     'Illustration',        10),
    ('art',        'photography',   'Fotoqrafiya',      'Photography',         20),
    ('music',      'albums',        'Albomlar',         'Albums',              10),
    ('film',       'documentary',   'Sənədli film',     'Documentary',         10),
    ('film',       'short',         'Qısametrajlı',     'Short film',          20),
    ('publishing', 'books',         'Kitablar',         'Books',               10),
    ('publishing', 'comics',        'Komikslər',        'Comics',              20),
    ('food',       'restaurants',   'Restoranlar',      'Restaurants',         10),
    ('food',       'craft',         'Yerli istehsal',   'Craft producers',     20),
    ('fashion',    'apparel',       'Geyim',            'Apparel',             10),
    ('fashion',    'accessories',   'Aksesuarlar',      'Accessories',         20),
    ('community',  'education',     'Təhsil',           'Education',           10),
    ('community',  'environment',   'Ətraf mühit',      'Environment',         20)
) AS seed (category_slug, slug, name_az, name_en, sort_order)
    ON seed.category_slug = categories.slug;
