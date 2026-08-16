-- The indexes the discovery query API (#42) reads through.
--
-- Nothing here changes what is stored. Every statement is a CREATE INDEX, so this
-- migration is safe in both halves of a rolling deployment: the previous release
-- simply does not use them.
--
-- WHY THEY ARE ALL PARTIAL. Every query this module issues begins with the same
-- clause -- the nine states of §6.1 that the public may see -- because a campaign
-- in one of the other seven must never appear in a feed whatever was filtered for.
-- A partial index over exactly those nine is therefore not an optimisation of a
-- common case, it is an index over the whole of what discovery can ever read. It
-- is also much smaller than the equivalent total index: every DRAFT on the
-- platform is excluded, and drafts outnumber launched campaigns on every
-- crowdfunding platform there has ever been.
--
-- The predicate is written out as nine literals rather than derived, for the same
-- reason `projects_state_known` is: the state vocabulary lives in a CHECK
-- constraint and in Java, and a tenth state added later has to be a deliberate
-- decision here too. `DiscoveryStatus.PUBLIC_STATES` is the Java half and
-- `DiscoveryStatusTests` is what fails if the two disagree.
--
-- WHAT IS DELIBERATELY NOT HERE.
--
--   * No index for `sort=popularity`. The score is an expression over two columns
--     and a request parameter (the instant the scroll started), so it is not
--     indexable at all -- an expression index would have to bake in one instant.
--     PostgreSQL sorts the matching rows instead. At §11.1's tier-1 ceiling of
--     roughly ten thousand campaigns that is a sort of a few thousand rows; past
--     it, popularity is one of the things that moves to the engine.
--   * No index on tags. V11's `project_tags_tag_idx (tag_id, project_id)` already
--     serves "which campaigns carry this tag", and the primary key
--     `(project_id, tag_id)` serves the counting probe the AND filter uses.
--     Adding a third would be a third thing to maintain on every write.
--   * No GIN index. `search_vector` does not exist; #43 brings the column and its
--     index together, which is the only way round that works -- an index on a
--     generated column has to be created after the column.
--
-- Reverse:
--   DROP INDEX IF EXISTS projects_discovery_newest_idx;
--   DROP INDEX IF EXISTS projects_discovery_ending_soon_idx;
--   DROP INDEX IF EXISTS projects_discovery_most_funded_idx;
--   DROP INDEX IF EXISTS projects_discovery_most_backed_idx;
--   DROP INDEX IF EXISTS projects_discovery_taxonomy_idx;
--   Reversing this loses no data and no history. It makes every discovery query a
--   sequential scan of `projects` and a sort, which is survivable at small scale
--   and is not survivable at §20's thousand requests a second -- so the way back
--   from a bad release remains the previous build of the application, and this
--   block is here because it is genuinely the whole of the undo.

-- ---------------------------------------------------------------------------
-- One index per keyset order
-- ---------------------------------------------------------------------------

-- WHY THE TIEBREAKER IS IN THE INDEX. Each of these ends in `id`, matching the
-- ORDER BY exactly, because the cursor is a keyset over (sort key, id): the
-- predicate on the second page is `key < :k OR (key = :k AND id > :i)`, and an
-- index that stops at the key can locate the start of the tie but then has to
-- filter and re-sort the whole run of rows sharing it. Thousands of campaigns
-- share a pledged_amount of zero.
--
-- WHY THE NULL DIRECTION IS SPELLED OUT. PostgreSQL defaults to NULLS FIRST for
-- DESC and NULLS LAST for ASC. The queries sort nulls last in both directions --
-- a campaign that has not launched is not the newest thing on the platform, and
-- one with no deadline is not the soonest to end -- so an index that disagreed
-- with the ORDER BY would simply not be used, silently, and the only symptom
-- would be a sort node in a plan nobody was looking at.

-- Serves: `GET /v1/discover?sort=newest`, which is the default sort and therefore
-- the unfiltered feed -- the single hottest query in §20's discovery budget.
CREATE INDEX projects_discovery_newest_idx
    ON projects (launched_at DESC NULLS LAST, id ASC)
    WHERE state IN (
        'PRELAUNCH', 'LIVE', 'CANCELED', 'SUCCESSFUL', 'UNSUCCESSFUL',
        'COLLECTING', 'LATE_PLEDGE', 'FULFILLING', 'COMPLETED'
    );

-- Serves: `sort=ending_soon`.
--
-- V6 already has `projects_state_deadline_idx (state, deadline)`, and it is kept:
-- it serves the scheduler's "which live campaigns have reached their deadline",
-- which is a different query with a single equality on state. It cannot serve this
-- one, because leading with `state` puts the deadlines in nine separate runs that
-- have to be merged and sorted, and it carries no tiebreaker.
CREATE INDEX projects_discovery_ending_soon_idx
    ON projects (deadline ASC NULLS LAST, id ASC)
    WHERE state IN (
        'PRELAUNCH', 'LIVE', 'CANCELED', 'SUCCESSFUL', 'UNSUCCESSFUL',
        'COLLECTING', 'LATE_PLEDGE', 'FULFILLING', 'COMPLETED'
    );

-- Serves: `sort=most_funded`. No NULLS clause: the column is NOT NULL DEFAULT 0.
CREATE INDEX projects_discovery_most_funded_idx
    ON projects (pledged_amount DESC, id ASC)
    WHERE state IN (
        'PRELAUNCH', 'LIVE', 'CANCELED', 'SUCCESSFUL', 'UNSUCCESSFUL',
        'COLLECTING', 'LATE_PLEDGE', 'FULFILLING', 'COMPLETED'
    );

-- Serves: `sort=most_backed`. Likewise NOT NULL DEFAULT 0.
CREATE INDEX projects_discovery_most_backed_idx
    ON projects (backers_count DESC, id ASC)
    WHERE state IN (
        'PRELAUNCH', 'LIVE', 'CANCELED', 'SUCCESSFUL', 'UNSUCCESSFUL',
        'COLLECTING', 'LATE_PLEDGE', 'FULFILLING', 'COMPLETED'
    );

-- ---------------------------------------------------------------------------
-- One index for the taxonomy filter
-- ---------------------------------------------------------------------------

-- Serves: `GET /v1/discover?category=…`, and `?category=…&subcategory=…`.
--
-- V6 already has `projects_category_state_idx (category_id, state)`. This is not a
-- duplicate of it and it measurably wins: the partial predicate means the nine
-- publicly visible states need no comparison at all and the index holds only rows
-- discovery can ever return, which on a real platform is a minority of the table --
-- drafts outnumber launched campaigns everywhere. Against a seeded 50,000-row table
-- where 25% of rows are publicly visible, `?category=games&completion=25_to_50`
-- planned as a bitmap index scan on this index rather than on V6's.
--
-- `subcategory_id` is the second key column rather than the first because the two
-- are chosen in that order: a filter panel offers subcategories only once a
-- category is picked, so `(category_id)` alone and `(category_id, subcategory_id)`
-- are the two shapes that are actually sent. A subcategory filter with no category
-- falls back to a bitmap scan over the partial index, which is the correct trade --
-- an index for a combination no interface produces would be maintained on every
-- write and read on none.
--
-- WHAT IS NOT HERE, AND WHY IT IS NOT.
--
-- An earlier version of this migration added an INCLUDE payload -- state,
-- goal_amount, pledged_amount, backers_count -- so that the facet query's scan over
-- every publicly visible campaign could be index-only. EXPLAIN says it does not
-- work: the planner reaches those rows through a *bitmap* scan, and a bitmap scan
-- always visits the heap, so the payload was never read. It was pure write
-- amplification on columns the pledge module updates on every pledge, and it is
-- gone.
--
-- The facet query needs no index of its own. Its base CTE is "every publicly
-- visible campaign", and every partial index in this file answers that; the planner
-- picks whichever is narrowest, which is this one, and built a bitmap of 12,500 rows
-- from it in 0.6ms against the seeded table.
--
-- WHERE THE CEILING IS. The facet scan is O(publicly visible campaigns) and no
-- index changes that: a count over every matching row has to touch every matching
-- row. Measured at 105ms for 12,500 of them, which is inside §20's 300ms and is
-- plainly linear. §11.1 puts tier 1 at roughly ten thousand campaigns and names
-- "when faceting becomes complex" as the other trigger for tier 2; this query is
-- that sentence made concrete. The step before tier 2 is not another index -- it is
-- caching the panel per filter set, because a filter panel changes far less often
-- than a feed page does.
CREATE INDEX projects_discovery_taxonomy_idx
    ON projects (category_id, subcategory_id)
    WHERE state IN (
        'PRELAUNCH', 'LIVE', 'CANCELED', 'SUCCESSFUL', 'UNSUCCESSFUL',
        'COLLECTING', 'LATE_PLEDGE', 'FULFILLING', 'COMPLETED'
    );

COMMENT ON INDEX projects_discovery_newest_idx IS
    'Keyset order for GET /v1/discover?sort=newest, the default sort. Partial over the nine publicly visible states.';
COMMENT ON INDEX projects_discovery_taxonomy_idx IS
    'Category and subcategory filter for GET /v1/discover. Also the cheapest way to enumerate publicly visible rows.';
