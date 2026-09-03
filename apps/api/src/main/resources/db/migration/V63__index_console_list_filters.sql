-- The indexes behind the console's new filters -- issue #404.
--
-- ---------------------------------------------------------------------------
-- Reverse:
--   DROP INDEX IF EXISTS transactions_status_idx;
--   DROP INDEX IF EXISTS projects_directory_slug_trgm_idx;
--   DROP INDEX IF EXISTS users_name_trgm_idx;
--   DROP INDEX IF EXISTS users_slug_trgm_idx;
--
--   Lossless, and safe in both directions at any moment. Every statement here
--   creates an index and none of them changes a row, a column or a constraint,
--   so a deployment that has this migration and one that does not answer every
--   query identically and differ only in how long the answer takes. There is
--   nothing to expand and nothing to contract.
--
--   What reversing costs is the reason the indexes exist: the four reads below
--   fall back to sequential scans. On today's data -- thirty-three campaigns,
--   nine hundred accounts -- that is imperceptible. On the data §22.1 plans for
--   it is a console screen that stops answering.
-- ---------------------------------------------------------------------------
--
-- ---------------------------------------------------------------------------
-- WHY THESE FOUR, AND WHY THE MIGRATION COMES WITH THE FEATURE
-- ---------------------------------------------------------------------------
--
-- #404 is a report that four console lists work at seed scale and stop working
-- at real scale: the campaign directory has no search at all, and the payment
-- log -- whose own copy says it includes rejected calls -- cannot select them.
--
-- Every one of those filters is one line of SQL. What made them absent was not
-- the line; it was that `PaymentLogScope` and `CampaignDirectory` both declined
-- to offer a filter they could not serve from an index, on the argument that
-- the first person to run a sequential scan over the platform's largest table
-- is a moderator on a Tuesday rather than a load test. That argument was right,
-- and it is answered here rather than overruled: the index lands in the same
-- change as the filter it makes affordable.
--
-- ---------------------------------------------------------------------------
-- 1. Provider calls by outcome
-- ---------------------------------------------------------------------------
--
-- "Every failed charge on the platform" is the main reason to open
-- `/admin/payments`, and it was the one view the screen could not select. V41
-- gave `transactions` an index on `(pledge_id, created_at DESC)` and one on
-- `(project_id, created_at DESC)`; neither leads on the status, so a filter on
-- it alone read the whole table.
--
-- `(status, id DESC)` rather than `(status, created_at DESC)`, because the log
-- is ordered by the identifier: it is a UUID v7 carrying the millisecond it was
-- minted in (§7.3), it is unique where the timestamp is not, and a unique sort
-- key is a cursor of one value instead of two. `PaymentTransactionRepository`
-- carries that argument, and this index is the shape of the query it produces --
-- the leading equality, then the sort column, so a page of failures is a range
-- scan rather than a sort of every failure ever recorded.
--
-- Not partial over `FAILED`. The screen offers all three outcomes, `PENDING` is
-- the one an operator chases at the end of a collection run, and a full index on
-- a three-value column costs a fraction of the table it covers.
CREATE INDEX transactions_status_idx ON transactions (status, id DESC);

COMMENT ON INDEX transactions_status_idx IS
    'AD-05''s outcome filter: one status, newest first. Ordered by id because the log is -- see V41.';

-- ---------------------------------------------------------------------------
-- 2. Campaign search
-- ---------------------------------------------------------------------------
--
-- The campaign directory is the only screen that lists campaigns in every state
-- and it had no input of any kind: finding one campaign among hundreds meant
-- paging through sixteen status chips and reading. It now searches by title,
-- by creator, and by identifier.
--
-- The title half already has its index. V13 built
-- `projects_search_title_trgm_idx` over `ideanest_fold(title)` for public
-- search's typo tolerance, and a folded `LIKE '%…%'` from the console reads the
-- same index -- which is why the console's search folds ə→e and ı→i exactly as
-- public search does, and why an operator can type "kohne" and find "köhnə".
--
-- What was missing is the other three columns.
--
-- `projects.slug` has a unique btree from V6 and a btree cannot serve a
-- contains-match, so a search for a fragment of a path scanned the table.
CREATE INDEX projects_directory_slug_trgm_idx
    ON projects USING GIN (slug gin_trgm_ops);

COMMENT ON INDEX projects_directory_slug_trgm_idx IS
    'Contains-match on a campaign path, for the console directory''s search. §11.3 folding is '
    'not applied: a slug is already folded and lower-cased by Slugs.of when it is allocated.';

-- The creator half. A campaign names its creator by identifier only, so
-- searching for one by name means matching `users`, and there was no index that
-- could: V2 gives `users` a unique btree on the slug and on the address, and
-- nothing at all on the name.
--
-- Two indexes rather than one over both columns: `gin_trgm_ops` is a per-column
-- operator class and a composite GIN over two of them answers neither query on
-- its own. They are small -- a name is short, and a trigram index over nine
-- hundred of them is a few tens of kilobytes.
--
-- Folded by `ideanest_fold` rather than by `lower`, so that the creator half of
-- a search behaves like the title half beside it. A console that found "Köhnə
-- Şəhər" by title from "kohne" and not by creator name from "kohne" would be
-- one search box with two spellings of the same rule behind it.
--
-- **`/admin/users`'s own search is not moved onto these, and does not use
-- them.** That query is JPQL -- `lower(u.name) LIKE :term`, from #104 -- and
-- JPQL cannot name a database function without registering it with Hibernate;
-- an expression index only serves a query that repeats the expression exactly.
-- So the account directory keeps reading every row, exactly as it did before
-- this migration, and it is no worse for it. Making it faster is a change to
-- that query rather than to this file, and doing it here would mean the index
-- and the query that needs it landing in different releases.
CREATE INDEX users_name_trgm_idx
    ON users USING GIN (ideanest_fold(name) gin_trgm_ops);

COMMENT ON INDEX users_name_trgm_idx IS
    'Contains-match on a display name: the console''s account search, and the creator half of '
    'the campaign directory''s. Folded by §11.3 so that "kohne" finds "Köhnə".';

CREATE INDEX users_slug_trgm_idx
    ON users USING GIN (ideanest_fold(slug) gin_trgm_ops);

COMMENT ON INDEX users_slug_trgm_idx IS
    'The same for a profile path, which is what a complaint about a creator usually carries.';
