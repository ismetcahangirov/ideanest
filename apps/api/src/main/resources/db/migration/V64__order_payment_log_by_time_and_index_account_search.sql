-- The indexes behind two console reads that were ordered or folded wrongly --
-- issues #412 and #413.
--
-- ---------------------------------------------------------------------------
-- Reverse:
--   DROP INDEX IF EXISTS users_email_trgm_idx;
--   DROP INDEX IF EXISTS transactions_created_at_idx;
--   DROP INDEX IF EXISTS transactions_status_created_idx;
--
--   Lossless, and safe in both directions at any moment. Every statement here
--   creates an index and none of them changes a row, a column or a constraint,
--   so a deployment that has this migration and one that does not answer every
--   query identically and differ only in how long the answer takes. There is
--   nothing to expand and nothing to contract.
--
--   What reversing costs is the reason the indexes exist: the payment log's two
--   unscoped reads sort every row they can see, and the account directory reads
--   all of `users`. On today's data -- nine hundred accounts, and a transaction
--   table that has collected nothing yet -- that is imperceptible. On the data
--   §22.1 plans for, the first is a screen that stops answering and the second
--   is a search that gets slower every week.
-- ---------------------------------------------------------------------------
--
-- ---------------------------------------------------------------------------
-- 1. THE PAYMENT LOG IS ORDERED BY THE COLUMN IT DISPLAYS -- #412
-- ---------------------------------------------------------------------------
--
-- `/admin/payments` describes itself as newest first and was ordered by a column
-- it does not display. V63 built `transactions_status_idx` as `(status, id DESC)`
-- and said why: the log was ordered by the identifier, because a UUID v7 carries
-- the millisecond it was minted in (§7.3), it is unique where the timestamp is
-- not, and a unique sort key is a cursor of one value instead of two.
--
-- That argument holds only while the two clocks agree, and they do not have to.
-- `transactions.id` is minted by the application when the row is built;
-- `created_at` is `DEFAULT now()` (V41) and is taken by the database when the
-- insert lands. A charge that mints its key before a provider call and commits
-- after it, two application instances whose clocks differ by a few
-- milliseconds, and anything seeded or migrated in with a key from elsewhere all
-- put the two orders out of step.
--
-- #404 found exactly this on `audit_logs`, where the cost was an investigator
-- scrolling past last month to reach this morning. Here the rows are retry
-- attempts against somebody's card and the order **is** the evidence: §9.6
-- permits four collection attempts, and "declined, declined, collected" read in
-- the wrong order is a different story about the same pledge. Within one pledge
-- `attempt_number` disambiguates them; the unfiltered log, which is what the
-- screen opens on, has nothing to fall back on.
--
-- So `PaymentTransactionRepository` now orders every one of its twelve reads by
-- `(created_at DESC, id DESC)` -- the column the screen shows, with the key
-- breaking the tie -- and these two indexes are the shape of the two reads that
-- had none for that order.

-- The outcome filter. `(status, created_at DESC, id DESC)`: the leading equality,
-- then the sort columns, so a page of failures is a range scan rather than a sort
-- of every failure ever recorded. It replaces V63's `(status, id DESC)`, which
-- was the same index for the ordering this issue removed.
--
-- Not partial over `FAILED`, for V63's reason: the screen offers all three
-- outcomes, `PENDING` is the one an operator chases at the end of a collection
-- run, and a full index on a three-value column costs a fraction of the table it
-- covers.
CREATE INDEX transactions_status_created_idx ON transactions (status, created_at DESC, id DESC);

COMMENT ON INDEX transactions_status_created_idx IS
    'AD-05''s outcome filter: one status, newest first by created_at -- the column the screen '
    'renders. Supersedes transactions_status_idx, which ordered by the identifier. See #412.';

-- The unfiltered read, which is the view `/admin/payments` opens on and the one
-- with nothing to fall back on. It walked the primary key before this, which was
-- free and was the wrong order; it now walks a column that had no index at all.
-- That is the one real cost of #412, and it is one statement.
CREATE INDEX transactions_created_at_idx ON transactions (created_at DESC, id DESC);

COMMENT ON INDEX transactions_created_at_idx IS
    'AD-05''s default view: every provider call, newest first. Carries id so the keyset is '
    'exact when four attempts share one second -- see #412.';

-- BOTH CARRY `id`, WHERE V21'S AUDIT INDEXES DO NOT. The keyset predicate is
-- `(created_at, id) < (?, ?)`, so an index ending in `created_at` alone makes it
-- nearly exact -- the tie is resolved by re-reading the handful of rows sharing
-- one instant. `AuditEntryRepository` accepted that because adding `id` to V21's
-- four indexes would be a rebuild on a table that only grows. These two are new,
-- so the exact form costs one extra column on an index that did not exist
-- yesterday, and §9.6's four attempts inside one second make the tie the ordinary
-- case here rather than the edge one.
--
-- V41'S TWO SCOPED INDEXES ARE UNTOUCHED AND GOT BETTER.
-- `transactions_pledge_idx` and `transactions_project_idx` are both
-- `(…, created_at DESC)`, which is now the order the query asks for instead of an
-- order it had to sort away. Six of the twelve reads are cheaper after #412 than
-- before it.
--
-- WHAT THIS MIGRATION DELIBERATELY DOES NOT DO: drop
-- `transactions_status_idx`. Nothing will use it once #412 is deployed, and it
-- is not dropped here because §20's rolling deployment means instances running
-- the previous release are still issuing `WHERE status = ? ORDER BY id DESC`
-- against this table while this migration is already applied. Expand now,
-- contract later: the drop belongs in the release after the one that ships
-- #412, by which time no running instance can ask for that order.
--
--   DROP INDEX IF EXISTS transactions_status_idx;   -- next release, not this one
--
-- ---------------------------------------------------------------------------
-- 2. THE ACCOUNT DIRECTORY SEARCHES THE WAY EVERY OTHER SEARCH DOES -- #413
-- ---------------------------------------------------------------------------
--
-- V63 built `users_name_trgm_idx` and `users_slug_trgm_idx` over
-- `ideanest_fold(...)`, and said plainly that `/admin/users` did not use them:
-- that query is JPQL, it matched on `lower()`, and JPQL cannot name a database
-- function without registering it with Hibernate. So the account directory kept
-- reading every row, and -- the half staff noticed first -- it folded
-- differently from every other search on the platform. `lower()` leaves ə, ı, ö,
-- ü, ğ, ş and ç alone, so it found "Köhnə" from `köhnə` and not from `kohne`,
-- while the campaign directory beside it found both.
--
-- `SearchFoldFunctionContributor` is the registration, and the query now names
-- `ideanest_fold` like everything else. What was missing on this side is one
-- index, and the reason it is needed is not that the address is important: it is
-- that **a disjunction is only as indexed as its least indexed branch.** The
-- search matches three columns with `OR`, so leaving `lower(email)` unindexed
-- would leave PostgreSQL no choice but a sequential scan -- and V63's two
-- indexes, which are correct and now nameable, would still never be read.
--
-- Folded rather than merely lower-cased, so that all three branches of one `OR`
-- are one rule. `users.email` is `citext` and already case-insensitive for
-- equality, which is what its unique index from V2 is for; a contains-match is a
-- different question and a btree cannot answer it either way.
--
-- `ideanest_fold(email)` and not `ideanest_fold(email::text)`: PostgreSQL's
-- implicit citext-to-text cast resolves the call, and this is written exactly as
-- the query writes it. An expression index serves a query that repeats the
-- expression, which is the whole reason this file exists.
CREATE INDEX users_email_trgm_idx
    ON users USING GIN (ideanest_fold(email) gin_trgm_ops);

COMMENT ON INDEX users_email_trgm_idx IS
    'Contains-match on an address, for the console''s account search. Folded by §11.3 so that '
    'all three branches of that search''s OR can be served from an index -- see #413.';
