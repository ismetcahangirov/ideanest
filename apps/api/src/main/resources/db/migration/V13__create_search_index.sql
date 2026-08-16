-- Full-text search with diacritic folding (#43). §4.3 D-01 and D-03, §11.1, §11.3.
--
-- V6 left `search_vector` out of `projects` on purpose and said why: "its
-- definition is the ranking decision that issue exists to make". This is that
-- decision, written down.
--
-- Nothing here removes or narrows anything. One nullable column, three functions,
-- two triggers, two indexes, and one backfill -- so both halves of a rolling
-- deployment are safe: the previous release neither reads the column nor knows
-- the triggers exist, and the triggers only ever write the new column.
--
-- Reverse:
--   DROP INDEX IF EXISTS projects_search_title_trgm_idx;
--   DROP INDEX IF EXISTS projects_search_vector_idx;
--   DROP TRIGGER IF EXISTS users_search_vector_au ON users;
--   DROP TRIGGER IF EXISTS projects_search_vector_biu ON projects;
--   DROP FUNCTION IF EXISTS users_refresh_project_search_vectors();
--   DROP FUNCTION IF EXISTS projects_refresh_search_vector();
--   ALTER TABLE projects DROP COLUMN IF EXISTS search_vector;
--   DROP FUNCTION IF EXISTS ideanest_project_search_vector(text, text, jsonb, text);
--   DROP FUNCTION IF EXISTS ideanest_story_text(jsonb);
--   DROP FUNCTION IF EXISTS ideanest_fold(text);
--   Reversing this loses no data: `search_vector` is derived from title, blurb,
--   story, and the creator's name, and re-running the migration rebuilds it from
--   those columns. What it loses is every text query -- `GET /v1/search` and
--   `?q=` on `/v1/discover` would have to be refused again through
--   DiscoveryCapability.FULL_TEXT, which is the previous build of the
--   application. So the way back from a bad release is that build, and this
--   block is genuinely the whole of the undo.
--
-- A NOTE ON THE DATABASE LOCALE. `lower()` and the text-search parser downcase
-- according to the database's ctype. Every folded value below therefore assumes
-- the database was created with a UTF-8 locale rather than with `C`, under which
-- `lower('ПРИВЕТ')` is a no-op and Russian titles (§21.1) would be indexed in two
-- cases. The platform already depends on this -- `citext` in V1 and every
-- `lower(btrim(...))` constraint in V6 do -- and it is stated here because this
-- migration is the first thing that would produce quietly wrong *results* rather
-- than a constraint violation if it were untrue.

-- ---------------------------------------------------------------------------
-- 1. The fold (§11.3)
-- ---------------------------------------------------------------------------

-- WHY NOT `unaccent`. It is the obvious answer and it is the wrong one, twice.
--
--   * It does not fold `ə`. Measured on postgres:16-alpine:
--         unaccent('Əşya ışıq öz üçün İncəsənət') = 'Əsya isiq oz ucun Incəsənət'
--     Six of §11.3's seven pairs are handled; the schwa -- the character §11.3
--     names first, and the most common letter in written Azerbaijani -- passes
--     through untouched, because it is a letter of the alphabet rather than an
--     accented `e`. A search for "secenek" would not find "seçənək", which is
--     exactly the requirement.
--   * It is STABLE, not IMMUTABLE (`provolatile = 's'`), because it reads a
--     dictionary file that can be reloaded. PostgreSQL therefore refuses it in a
--     generated column and in an index expression. The usual workaround is an
--     IMMUTABLE wrapper that lies about the volatility of what it calls, and the
--     lie comes true the day somebody edits `unaccent.rules`: every index built
--     from it is silently wrong and nothing reports it.
--
-- So: an explicit mapping of exactly the fourteen characters §11.3 names, seven
-- pairs in both cases. It is genuinely immutable -- `translate` and `lower` of a
-- constant map -- so it can carry an expression index, and it folds precisely
-- what the specification asks for and nothing else.
--
-- THE ORDER MATTERS. Folding runs *before* lower-casing and covers both cases of
-- each letter, because `lower('İ')` is `i` followed by a combining dot above in
-- some ctypes and a plain `i` in others. Mapping `İ` directly to `i` makes the
-- result the same everywhere, which is what lets a Java caller and this function
-- agree -- see `Slugs.fold`, and `SearchFoldingTests`, which pins them to each
-- other over a shared table of cases.
CREATE FUNCTION ideanest_fold(value text) RETURNS text
    LANGUAGE sql
    IMMUTABLE
    PARALLEL SAFE
    STRICT
    AS $$
        SELECT lower(translate(value, 'əƏıİöÖüÜğĞşŞçÇ', 'eeiioouuggsscc'))
    $$;

COMMENT ON FUNCTION ideanest_fold(text) IS
    '§11.3: folds ə→e, ı→i, ö→o, ü→u, ğ→g, ş→s, ç→c in both cases, then lower-cases. '
    'Immutable, so it can carry an index. Mirrored in Java by az.ideanest.shared.Slugs.fold.';

-- ---------------------------------------------------------------------------
-- 2. The prose inside a story document
-- ---------------------------------------------------------------------------

-- `projects.story` is jsonb (V6), and D-01 says the story is searchable. Casting
-- the document to text would index its structure as well as its content: every
-- campaign on the platform would match "paragraph", "spans", and "marks", and the
-- word "type" would be the most common lexeme in the corpus.
--
-- So the prose is extracted by jsonpath, and it is *the same* prose §5.3 counts --
-- headings, paragraph and quote spans, and list items. Deliberately not an
-- image's `alt` or an embed's `title`: those describe media rather than being the
-- story, and `StoryDocuments.characterCount` draws the line in the same place. Two
-- definitions of "the text of a story" would be one too many.
--
-- Lax mode (the default) is what makes this total: a block with no `text`, a
-- document with no `blocks`, and a story written by a future schema version all
-- yield nothing rather than an error. A function that could throw would be a
-- function that can refuse an UPDATE to a campaign, from inside a trigger.
CREATE FUNCTION ideanest_story_text(document jsonb) RETURNS text
    LANGUAGE sql
    IMMUTABLE
    PARALLEL SAFE
    STRICT
    AS $$
        SELECT coalesce(string_agg(prose, ' '), '')
          FROM jsonb_array_elements_text(
                   jsonb_path_query_array(document, '$.blocks[*].text')
                || jsonb_path_query_array(document, '$.blocks[*].spans[*].text')
                || jsonb_path_query_array(document, '$.blocks[*].items[*][*].text')
               ) AS extracted(prose)
    $$;

COMMENT ON FUNCTION ideanest_story_text(jsonb) IS
    'The reader-visible prose of a story document: heading text, paragraph and quote spans, '
    'and list items. Matches StoryDocuments.characterCount, and excludes image alt text and '
    'embed titles for the same reason it does.';

-- ---------------------------------------------------------------------------
-- 3. The vector
-- ---------------------------------------------------------------------------

-- WHICH TEXT CONFIGURATION: `simple`, which does no stemming and has no stop-word
-- list.
--
-- The alternative is `english`, and it is worse than doing nothing here. §21.1
-- puts the platform in four languages -- az, en, ru, tr -- and `projects` carries
-- no column saying which one a campaign is written in, so one configuration is
-- applied to every row whatever it holds. English stemming rules applied to
-- Azerbaijani are noise, but the stop-word list is the part that is actively
-- wrong: `at` is "horse", `on` is "ten", `an` is "moment", `am` is "belly", `il`
-- is "year". A campaign titled "At" would index to an empty vector under
-- `english` and be unfindable by its own name.
--
-- `simple` costs the platform English stemming -- searching "games" will not find
-- "game". That is a real cost and it is the smaller one, and D-03's trigram tier
-- below recovers part of it.
--
-- WEIGHTS. `ts_rank`'s defaults are {D, C, B, A} = {0.1, 0.2, 0.4, 1.0}:
--
--   A  title           what the campaign is called
--   B  blurb           §4.3's "summary"; one sentence the creator chose to
--                      describe the whole thing
--   C  creator name    D-01 makes the creator searchable, and a name is a strong
--                      signal when it is what was typed -- but somebody looking
--                      for "Robot" wants the campaign called Robot before the one
--                      by Robert Robotson
--   D  story           several thousand words, so an unweighted match here would
--                      let paragraph nine outrank a title. Measured: a title
--                      match ranks 0.607927 and a story match 0.060793, exactly
--                      the ten-to-one the weights ask for
--
-- The vector is built by a function rather than inline so that the trigger, the
-- backfill, and the `users` trigger below cannot drift apart -- three copies of
-- this expression is three chances for the index to disagree with itself.
CREATE FUNCTION ideanest_project_search_vector(
        title text, blurb text, document jsonb, creator_name text) RETURNS tsvector
    LANGUAGE sql
    IMMUTABLE
    PARALLEL SAFE
    AS $$
        SELECT setweight(to_tsvector('simple', ideanest_fold(coalesce(title, ''))), 'A')
            || setweight(to_tsvector('simple', ideanest_fold(coalesce(blurb, ''))), 'B')
            || setweight(to_tsvector('simple', ideanest_fold(coalesce(creator_name, ''))), 'C')
            || setweight(to_tsvector('simple', ideanest_fold(coalesce(ideanest_story_text(document), ''))), 'D')
    $$;

-- Nullable, and it stays nullable. A NOT NULL column with a default would rewrite
-- the whole table under an ACCESS EXCLUSIVE lock, and the previous release -- the
-- one still serving traffic during the deployment -- inserts campaigns without
-- knowing this column exists. The trigger fills it on every insert, so a null here
-- means a row written before this migration ran and nothing else; the backfill
-- below removes those, and `search_vector @@ query` is false for null anyway, so
-- the failure mode is a campaign that cannot be found rather than one that leaks.
ALTER TABLE projects ADD COLUMN search_vector tsvector;

COMMENT ON COLUMN projects.search_vector IS
    'D-01 full-text index over title (A), blurb (B), creator name (C), and story prose (D), '
    'folded by §11.3. Maintained by triggers -- see projects_search_vector_biu and '
    'users_search_vector_au. Never write it by hand.';

-- ---------------------------------------------------------------------------
-- 4. Keeping it true
-- ---------------------------------------------------------------------------

-- WHY A TRIGGER AND NOT `GENERATED ALWAYS AS`.
--
-- A generated column would be the better answer -- it cannot be forgotten, it
-- cannot be written by hand, and there is no second code path to keep in step.
-- PostgreSQL will not allow it here, and not for a reason a rewrite gets around:
-- a generated expression may reference only the row it belongs to, and D-01 puts
-- the *creator's name* in the index. That lives in `users`. A generated column
-- cannot see it, and neither can any function it calls, because such a function
-- would have to be marked IMMUTABLE while reading a table -- which is the same lie
-- as the `unaccent` wrapper above, and it comes true the first time somebody
-- renames themselves.
--
-- (The jsonb story is not the obstacle: `ideanest_story_text` is genuinely
-- immutable and would be legal in a generated column. Splitting the vector in two
-- -- a generated column for the same-row fields and a trigger-maintained one for
-- the creator -- would mean two columns, two GIN indexes, and a query that ORs
-- across both. One trigger-maintained column is the smaller thing to get right.)
--
-- THE FAILURE MODE TO AVOID is a vector that silently stops being updated: the
-- campaign is still returned by its old title for ever, and nothing anywhere
-- reports it. Two things guard against it. The trigger recomputes from `NEW.*`
-- rather than patching what it was given, so it cannot half-apply; and
-- `SearchTextTests` edits a title, edits a story, and renames a creator, and
-- asserts that each is findable by the new text and not by the old.
CREATE FUNCTION projects_refresh_search_vector() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
    BEGIN
        NEW.search_vector := ideanest_project_search_vector(
            NEW.title,
            NEW.blurb,
            NEW.story,
            (SELECT u.name FROM users u WHERE u.id = NEW.creator_id));
        RETURN NEW;
    END;
    $$;

-- BEFORE, so the value is written in the same tuple as the change rather than in
-- a second UPDATE of the row that has just been written.
--
-- `UPDATE OF` names the four columns the vector is built from, so that a pledge
-- landing -- which updates `pledged_amount` and `backers_count` and nothing else,
-- on the hottest write path on the platform -- does not rebuild a tsvector over
-- several thousand words of story. On INSERT the column list is ignored, which is
-- what PostgreSQL does and what is wanted: every new campaign gets a vector.
CREATE TRIGGER projects_search_vector_biu
    BEFORE INSERT OR UPDATE OF title, blurb, story, creator_id ON projects
    FOR EACH ROW
    EXECUTE FUNCTION projects_refresh_search_vector();

-- The other half of D-01. A creator's name is in the vector, so renaming an
-- account has to rewrite the vector of every campaign it owns -- otherwise a
-- campaign stays findable by a name its creator no longer has, which is not only
-- stale: §17.4 anonymises a departing account by rewriting `users.name` in place,
-- and an index that kept the old value would keep serving the name of somebody who
-- asked to be forgotten.
--
-- WHEN, so that an UPDATE touching `users` for any other reason costs nothing, and
-- so that writing the same name back is not a rewrite of every campaign.
--
-- This UPDATE sets only `search_vector`, which is not in the `UPDATE OF` list
-- above, so the projects trigger does not fire and there is no recursion. (It
-- would compute the same value if it did.)
CREATE FUNCTION users_refresh_project_search_vectors() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
    BEGIN
        UPDATE projects p
           SET search_vector = ideanest_project_search_vector(p.title, p.blurb, p.story, NEW.name)
         WHERE p.creator_id = NEW.id;
        RETURN NULL;
    END;
    $$;

CREATE TRIGGER users_search_vector_au
    AFTER UPDATE OF name ON users
    FOR EACH ROW
    WHEN (OLD.name IS DISTINCT FROM NEW.name)
    EXECUTE FUNCTION users_refresh_project_search_vectors();

-- ---------------------------------------------------------------------------
-- 5. Backfill
-- ---------------------------------------------------------------------------

-- Every campaign that existed before the column did. One statement rather than a
-- batched loop: this runs against a platform with a four-figure number of
-- campaigns (§11.1 puts tier 1's ceiling at ten thousand), the row lock is held
-- only for the length of the migration, and nothing reads the column until the
-- release that follows. If this table ever gets large enough for that to be
-- untrue, the shape to reach for is a batched backfill in a separate migration,
-- not a longer transaction.
--
-- The join is inner, exactly as the discovery query's is: `creator_id` is NOT NULL
-- with no ON DELETE clause, so the row is always there.
UPDATE projects p
   SET search_vector = ideanest_project_search_vector(p.title, p.blurb, p.story, u.name)
  FROM users u
 WHERE u.id = p.creator_id;

-- ---------------------------------------------------------------------------
-- 6. Indexes
-- ---------------------------------------------------------------------------

-- Partial over the same nine publicly visible states as every index in V12, and
-- for the same reason: discovery's first clause is always the visibility
-- predicate, so this is an index over the whole of what a text search can ever
-- read rather than an optimisation of a common case. Drafts outnumber launched
-- campaigns on every crowdfunding platform there has ever been, and a draft's
-- story is exactly as long as a live one's.
--
-- GIN rather than GiST: GIN is slower to update and far faster to search, and a
-- campaign's text changes when its creator edits it while the index is read on
-- every search on the platform.
CREATE INDEX projects_search_vector_idx
    ON projects USING GIN (search_vector)
    WHERE state IN (
        'PRELAUNCH', 'LIVE', 'CANCELED', 'SUCCESSFUL', 'UNSUCCESSFUL',
        'COLLECTING', 'LATE_PLEDGE', 'FULFILLING', 'COMPLETED'
    );

-- D-03, misspelling tolerance, and D-02's suggestions.
--
-- A tsvector match is exact on whole lexemes: "robto" finds nothing, however
-- obviously it was meant to be "robot". `pg_trgm` (enabled in V1, which says
-- "Search is built on it") is the second tier, over the folded *title* only --
-- a typo is something a person makes while typing the name of the thing they are
-- looking for, and trigram-matching several thousand words of story would be both
-- expensive and noisy.
--
-- WHEN THE FUZZY TIER ENGAGES, and it is not on every query: `PostgresSearchService`
-- asks first whether the exact tier matches anything at all -- one probe of the GIN
-- index above -- and only falls back when the answer is no. So a query that
-- succeeds never pays for trigrams, and a query that fails pays once. See the
-- FUZZY_THRESHOLD constant there for the number and how it was chosen.
--
-- This index serves the `LIKE '%…%'` in the suggestion endpoint outright. It does
-- not serve `word_similarity(…) >= 0.4`, which is a filter over the rows the
-- partial predicate already selects -- indexed word similarity needs the `<%`
-- operator and the `pg_trgm.word_similarity_threshold` GUC, which is session state
-- and is not worth carrying through a connection pool for a path that runs only
-- when a search has already found nothing. At tier-1 scale that filter is a scan of
-- a few thousand rows. Past it, this is one of the things that moves to the engine.
CREATE INDEX projects_search_title_trgm_idx
    ON projects USING GIN (ideanest_fold(title) gin_trgm_ops)
    WHERE state IN (
        'PRELAUNCH', 'LIVE', 'CANCELED', 'SUCCESSFUL', 'UNSUCCESSFUL',
        'COLLECTING', 'LATE_PLEDGE', 'FULFILLING', 'COMPLETED'
    );

COMMENT ON INDEX projects_search_vector_idx IS
    'D-01 full-text search. Partial over the nine publicly visible states, which is every row a '
    'text search can return.';
COMMENT ON INDEX projects_search_title_trgm_idx IS
    'D-03 misspelling tolerance and D-02 suggestions, over the folded title. Partial for the same '
    'reason.';
