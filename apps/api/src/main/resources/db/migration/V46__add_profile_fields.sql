-- §4.2's P-02 and P-03 (#276): the fields a profile editor edits, and the one
-- thing a profile has never had -- somewhere to put them.
--
-- `users.name`, `bio` and `avatar_url` have existed since V2 and no code has
-- ever written any of them: `User.setAvatarUrl` has no callers, and the only
-- assignment to `bio` anywhere is the `= null` inside `User.anonymise`. That is
-- the absence §4.2's block quote names as the reason the account navigation has
-- no profile entry. This migration adds the two columns and the one table the
-- write path needs; the write path itself is `PATCH /v1/me/profile`.
--
-- ---------------------------------------------------------------------------
-- Reverse:
--   DROP TABLE IF EXISTS user_social_links;
--   ALTER TABLE users DROP CONSTRAINT IF EXISTS users_bio_length;
--   ALTER TABLE users DROP CONSTRAINT IF EXISTS users_website_url_is_https;
--   ALTER TABLE users DROP CONSTRAINT IF EXISTS users_website_url_length;
--   ALTER TABLE users DROP COLUMN IF EXISTS website_url;
--   ALTER TABLE users DROP COLUMN IF EXISTS location_id;
--   -- Loses every link and every location a person put on their own profile,
--   -- which is somebody's statement about themselves rather than seed data. As
--   -- everywhere else in this directory, the way back from a bad release is the
--   -- previous release plus a restore, not this block run casually.
-- ---------------------------------------------------------------------------
--
-- ---------------------------------------------------------------------------
-- ROLLING DEPLOYMENT: THIS IS AN EXPAND-ONLY MIGRATION
-- ---------------------------------------------------------------------------
--
-- Two nullable columns, one new table, two new indexes and three CHECK
-- constraints, and nothing is removed or narrowed. The previous release does
-- not know any of it exists: its INSERTs into `users` name their columns and
-- omit these, which succeeds because both are nullable, and it never writes
-- `bio` at all -- so the one constraint that applies to a pre-existing column
-- cannot be violated by the release running beside this one. The contract half
-- is described on `users.location_id` below and its precondition is a decision
-- nobody has taken yet.
--
-- ---------------------------------------------------------------------------
-- WHY `bio` IS BOUNDED HERE AND WITH A VALIDATED CONSTRAINT
-- ---------------------------------------------------------------------------
--
-- `bio` is `text` with nothing constraining it, which was harmless while no
-- endpoint wrote it and is not harmless the moment one does: an unbounded text
-- field behind an authenticated PATCH is somewhere to park a megabyte, and the
-- profile page then serves it to everybody who visits.
--
-- 2000 characters. §4.2's about tab is a paragraph or two about a person, not a
-- campaign story -- that is `story_versions`, a versioned document with its own
-- table -- and the neighbouring `projects.blurb` is capped at 135 for the one
-- line under a title. 2000 is roughly 300 words: more than anybody writes about
-- themselves, and small enough that the public profile body stays a small body.
--
-- ADDED VALIDATED RATHER THAN `NOT VALID` PLUS A LATER `VALIDATE`, and the
-- reasoning is worth writing down because the two-step is the usual answer for
-- a CHECK on an existing column. The two-step buys two different things: it
-- avoids the full-table scan under ACCESS EXCLUSIVE, and it lets a constraint be
-- added while a release that could still violate it is running. Neither applies
-- here. No code path in either release writes `bio`, so no row can violate it --
-- every existing value is NULL, put there by V2's default absence of a write
-- path and by `User.anonymise`. And splitting it would put the VALIDATE in V47,
-- which another change owns; a constraint left NOT VALID is one PostgreSQL will
-- not use for planning and one a future migration has to remember. There is no
-- `NOT VALID` anywhere else in this directory, and this is not the row to start
-- the precedent on.
--
-- ---------------------------------------------------------------------------
-- WHY `website_url` REFUSES ANYTHING THAT IS NOT https://
-- ---------------------------------------------------------------------------
--
-- A user-supplied URL rendered as an anchor on a public page is two attacks at
-- once. `javascript:alert(1)` in an `href` is stored cross-site scripting, and
-- the scheme is the whole of the exploit; and a profile link is the cheapest
-- spam surface a platform has, because it is a free backlink on an indexable
-- page. Refusing every scheme but https closes the first completely and makes
-- the second at least an ordinary link.
--
-- The check is a shape check and not a validation of the address. Nothing on
-- this server fetches the URL -- see `OwnProfileResponse` on why -- so "is this
-- a real site" is not a question the database or the application can answer,
-- and pretending to answer it is worse than declining to. What is enforced is
-- the scheme, the absence of whitespace (a URL with a space in it is either a
-- mistake or an attempt to smuggle a second attribute through a template that
-- forgot to quote), and a length.
--
-- 512 characters. The de-facto browser ceiling is nearer 2048, but 2048
-- characters of query string is a payload rather than a link somebody typed, and
-- this column is read on to a page that already has to fit on a phone.
--
-- ---------------------------------------------------------------------------
-- WHY THE LOCATION IS A FOREIGN KEY AND NOT A STRING
-- ---------------------------------------------------------------------------
--
-- §7.2 names `location_id` on this row, and V16 already built the vocabulary it
-- points at: eighteen Azerbaijani cities, each with a folded slug and a name per
-- locale. A free-text "location" column would be the other design, and it would
-- put "Baku", "Bakı", "baki" and "BAKU, AZ" in four rows that discovery's
-- `?city=` filter matches none of.
--
-- The point of the shared vocabulary is that the two ends agree. A profile that
-- says Gəncə and a campaign that says Gəncə carry the same uuid, so the profile
-- can link into `/discover?city=gence` and land on the campaigns that are
-- actually there. That link is the reason this is a foreign key rather than a
-- convention.
--
-- NULLABLE, AND IT STAYS NULLABLE. Every account that exists has no location, so
-- NOT NULL would fail on the ALTER, and NOT NULL with a default would invent a
-- fact about where somebody lives -- which §17.4's position makes the worst
-- available option: personal data the platform does not need is data it does not
-- keep, and a location nobody entered is data nobody gave us. THE CONTRACT HALF
-- would need a product decision that a profile must state a location, which
-- nobody has taken and which this migration is not the place to take.
--
-- No ON DELETE clause, exactly as `projects.location_id` has none, and V16's
-- comment there is the argument: with no clause PostgreSQL refuses to delete a
-- location that rows still point at. A cascade would delete accounts because
-- somebody tidied a gazetteer, and a SET NULL would silently unfile them.

ALTER TABLE users
    ADD COLUMN website_url text;

ALTER TABLE users
    ADD COLUMN location_id uuid REFERENCES locations (id);

ALTER TABLE users
    ADD CONSTRAINT users_website_url_is_https
        CHECK (website_url IS NULL OR website_url ~ '^https://[^[:space:]]+$');

ALTER TABLE users
    ADD CONSTRAINT users_website_url_length
        CHECK (website_url IS NULL OR length(website_url) BETWEEN 12 AND 512);

ALTER TABLE users
    ADD CONSTRAINT users_bio_length
        CHECK (bio IS NULL OR length(btrim(bio)) <= 2000);

COMMENT ON COLUMN users.website_url IS
    'P-02 (#276). The person''s own site. https only: a javascript: scheme in an href is stored XSS, and '
    'the server never fetches this address, so its shape is the only thing that can be checked.';

COMMENT ON COLUMN users.location_id IS
    'P-02 (#276). Where this person is, from V16''s closed vocabulary -- the same eighteen rows discovery''s '
    '?city= filter matches, so a profile can link into /discover. Nullable: a location nobody entered is not a fact.';

-- ---------------------------------------------------------------------------
-- user_social_links
-- ---------------------------------------------------------------------------

-- §4.2's P-03. A table rather than a `jsonb` column on `users`, and the
-- difference is which of these rules the database enforces and which stay
-- conventions in one service.
--
-- In `jsonb`, "the platform is one of nine we support" and "one link per
-- platform" and "the URL is https" are three things the application checks and
-- nothing else does -- so a support query, a data fix, or the second write path
-- somebody adds in two years puts `{"platform":"myspace","url":"javascript:..."}`
-- straight into the column, and the profile page renders it. As columns they are
-- a CHECK, a unique index and a CHECK. The cost of the table is a join on a page
-- that already joins nothing; the cost of the column is that the vocabulary is a
-- convention.
--
-- THE PLATFORM SET IS CLOSED, AND CLOSED IS THE POINT. Nine values, chosen for
-- where the people this platform serves actually publish. A free `platform`
-- string would mean the client has to render an icon for a name it has never
-- seen, and the honest fallback for that is a generic link -- at which point the
-- field is decoration. Adding a tenth is a one-line migration and a deliberate
-- act, which is the right weight for a decision that adds an icon to every
-- client on three platforms.
--
-- WHY NOT A `label` COLUMN so somebody can name their own. Because the label
-- would be user-supplied text rendered as a link on a public page, which is the
-- spam surface `website_url` above already carries once. Once is enough.
--
-- THE PER-ACCOUNT CAP IS NOT HERE, and that is deliberate rather than an
-- oversight. What this table can enforce structurally is one row per platform,
-- which bounds an account at nine rows. The product cap -- five, because a
-- profile listing nine channels reads as a link farm rather than as a person --
-- is `ProfileEditing.MAX_SOCIAL_LINKS`, enforced on the one write path, because
-- it is a number that will change when somebody looks at the page and a CHECK
-- constraint is a migration to change. The database holds the invariant that
-- must never be violated; the service holds the number somebody chose.
--
-- POSITION IS DENSE AND ZERO-BASED, maintained by the service, which rewrites
-- the whole list on every edit -- the same shape and the same reason as
-- `survey_questions.position` (V35): the list is short, it is always sent whole,
-- and "move the third one up" is a rewrite of three rows rather than a numbering
-- scheme to reason about. No unique index on `(user_id, position)` for that
-- reason: the rewrite deletes and re-inserts, and a unique constraint would have
-- to be deferred to survive it.
--
-- ON DELETE CASCADE, unlike almost every other reference to `users`. Those are
-- restricted because they are financial records that outlive the person (§17.4);
-- this is the person's own list of their own accounts elsewhere, it is not a
-- record of anything the platform did, and there is nothing to reconcile. The
-- cascade is a backstop rather than the mechanism -- `users` rows are never
-- hard-deleted, so the erasure path is `AccountAnonymiser`, which deletes these
-- rows explicitly. §17.4 is emphatic about why: an erasure that leaves somebody's
-- Instagram address behind has not erased them.

CREATE TABLE user_social_links
(
    id         uuid        PRIMARY KEY,
    user_id    uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    -- text with a CHECK rather than an enum type, the convention this schema
    -- follows for every closed set (`projects.state`, `users.profile_visibility`,
    -- V45 argues it): a PostgreSQL enum cannot have a value removed, and adding
    -- one takes a lock a text column does not.
    platform   text        NOT NULL,
    url        text        NOT NULL,
    position   integer     NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT user_social_links_platform_is_known CHECK (platform IN (
        'INSTAGRAM', 'FACEBOOK', 'X', 'YOUTUBE', 'TIKTOK',
        'LINKEDIN', 'TELEGRAM', 'GITHUB', 'BEHANCE')),
    -- The same rule as `users_website_url_is_https`, stated twice because it is
    -- enforced on two columns and a shared rule that lives in one of them is a
    -- rule the other stops having.
    CONSTRAINT user_social_links_url_is_https CHECK (url ~ '^https://[^[:space:]]+$'),
    CONSTRAINT user_social_links_url_length CHECK (length(url) BETWEEN 12 AND 512),
    CONSTRAINT user_social_links_position_is_not_negative CHECK (position >= 0)
);

-- One link per platform per account. Two Instagram links is a mistake in every
-- reading of it, and the unique index is also what bounds an account's rows at
-- the size of the vocabulary above.
CREATE UNIQUE INDEX user_social_links_account_platform_key
    ON user_social_links (user_id, platform);

-- The only way this table is ever read: every link of one account, in order.
CREATE INDEX user_social_links_account_idx
    ON user_social_links (user_id, position);

CREATE TRIGGER user_social_links_set_updated_at
    BEFORE UPDATE ON user_social_links
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON TABLE user_social_links IS
    'P-03 (#276). A person''s accounts elsewhere. A table rather than jsonb so the vocabulary, the '
    'one-per-platform rule and the https-only rule are constraints instead of conventions.';

COMMENT ON COLUMN user_social_links.platform IS
    'One of nine. Closed so that a client always has an icon for it; a tenth is a deliberate migration.';

COMMENT ON COLUMN user_social_links.position IS
    'Dense and zero-based. The service rewrites the whole list on every edit, as survey_questions does.';
