-- §4.7's CD-10 (#97): a saved filter over a campaign's backers, and the index the
-- report reads them by.
--
-- ---------------------------------------------------------------------------
-- WHAT A SEGMENT IS, AND WHAT IT IS NOT
-- ---------------------------------------------------------------------------
--
-- A segment is **a filter with a name**. It stores the question — "backers in
-- Germany who took the early-bird tier" — and never the answer. Nothing here
-- holds a backer identifier, and that is the whole design rather than an
-- omission:
--
--   * A stored membership list is wrong the moment somebody pledges. A campaign
--     running for thirty days would have segments that silently drift out of
--     date, and the first time anybody notices is when #98 messages a segment
--     and the people who joined last week are not in it.
--   * A stored list is also personal data with a second retention rule. The
--     pledge row already carries who backed what; copying that into a marketing
--     object means deleting an account has two places to reach, and §17.4 has
--     no mechanism that would find the second.
--
-- So the filter is re-evaluated on every read, against `pledges`, which is the
-- ledger and therefore the answer.
--
-- ---------------------------------------------------------------------------
-- WHY COLUMNS AND NOT JSONB
-- ---------------------------------------------------------------------------
--
-- The obvious shape for "a saved filter" is one `jsonb` document, and it was
-- rejected for the reason §10.4 rejects an unvalidated document on a public
-- page: what it costs is that the database stops being able to say what a
-- filter *is*. A `states` array whose members must be five known constants is
-- checkable; `filter->'states'` is whatever the release that wrote it believed.
--
-- Four columns, four checks, and a filter that fails to parse cannot be stored
-- in the first place. The cost is a migration when the report gains an axis,
-- which is the right cost: a new axis is a change to what the screen offers and
-- to the API contract, so it was never going to be free.
--
-- **NULL means "any", not "none".** A segment with `countries IS NULL` is not a
-- segment matching no country; it is one that does not filter by country at
-- all. An empty array would mean the same thing more ambiguously, so the checks
-- below refuse one: every array here is either absent or has at least one
-- member.
--
-- ---------------------------------------------------------------------------
-- THE STATE VOCABULARY, AND WHY ONLY FIVE OF TWELVE
-- ---------------------------------------------------------------------------
--
-- `PledgeState` has twelve members and this check names five: the campaign's
-- backers are `PledgeState.ACTIVE` minus `DRAFT`, which is the set
-- `PublicBackers.COUNTED` already derives and the project page already counts.
-- A draft is a five-minute reservation (§4.5's PL-13) and not a person who
-- backed anything; the terminal states — expired, cancelled, refunded, dropped,
-- charged back — are somebody who is no longer a backer, and the screen that
-- exists for them is CD-17's collection status, which is not built.
--
-- Restating the five here rather than deriving them is the same decision
-- `PledgeState.ACTIVE` makes about the partial unique index it mirrors, and it
-- has the same guard: `BackerSegmentSchemaTests` asserts this constraint
-- against the enum rather than against a copy of it, so the two cannot drift
-- without a red build.
--
-- ---------------------------------------------------------------------------
-- Rolling deployment
-- ---------------------------------------------------------------------------
--
-- One new table, one unique index on it, and one index on `pledges`. Nothing is
-- dropped, no column is altered, and no previous release reads or writes any of
-- it. Both halves of a rolling deploy are safe in either order. EXPAND with no
-- contract half.
--
-- `pledges_backer_report_idx` is created without CONCURRENTLY, which is what
-- Flyway's transactional migration requires, and it therefore takes an ACCESS
-- EXCLUSIVE lock on `pledges` for as long as the build runs. V27's header made
-- the same note about `referral_attributions` and the same answer applies with
-- less force: `pledges` gains a row per checkout on a platform that has not
-- launched, so the build is seconds at the outside. The consequence is for the
-- next index rather than this one — once this table carries a campaign's worth
-- of history, an index added to it needs CONCURRENTLY and therefore a migration
-- that is not in a transaction.
--
-- Reverse:
--   DROP INDEX IF EXISTS pledges_backer_report_idx;
--   DROP TABLE IF EXISTS backer_segments;
--
--   Dropping the table loses saved filters and loses nothing else: no pledge,
--   no backer, and no money is referenced by it. A creator would have to name
--   their segments again, which is the entire blast radius.

-- ---------------------------------------------------------------------------
-- backer_segments — one saved filter over one campaign's backers
-- ---------------------------------------------------------------------------
CREATE TABLE backer_segments (
    id              uuid        PRIMARY KEY,

    -- The campaign the filter is about. A segment cannot outlive it: there is
    -- nothing for the filter to be evaluated against once the campaign is gone,
    -- and a saved filter pointing at nothing is a row that only ever produces an
    -- error message.
    project_id      uuid        NOT NULL REFERENCES projects (id) ON DELETE CASCADE,

    -- What the creator called it. Displayed as it was typed; compared folded, by
    -- the unique index below.
    name            text        NOT NULL,

    -- Which pledge states count. NULL is every state the report covers.
    states          text[],

    -- Which reward tiers. NULL is every tier, and it is not the same as "the
    -- tiers that exist today" — a tier added tomorrow is inside a NULL segment
    -- and outside an enumerated one, which is what a creator means by each.
    --
    -- No foreign key, because an array cannot carry one. The read is a join
    -- against `reward_tiers` and an identifier naming a tier that has been
    -- removed simply matches nothing; a dangling member is a filter that has
    -- become narrower, not a query that fails.
    reward_tier_ids uuid[],

    -- ISO 3166-1 alpha-2, matching `pledges.shipping_country`. NULL is every
    -- destination, including the pledges that named none.
    countries       text[],

    -- The free-text search: a name or an email address, or part of one. NULL is
    -- no search rather than an empty one, for the same reason the arrays refuse
    -- to be empty.
    term            text,

    -- Who saved it. Not who may use it: any collaborator holding VIEW_FINANCES
    -- on the campaign reads and edits the campaign's segments, because a segment
    -- is the campaign's working vocabulary and not one person's bookmark. This
    -- column is for the support conversation that starts "who set this up".
    --
    -- ON DELETE is deliberately absent, so the default NO ACTION stands: §17.4
    -- anonymises an account rather than deleting the row, so this reference
    -- stays valid, and a segment must not disappear because the collaborator who
    -- named it left the platform.
    created_by      uuid        NOT NULL REFERENCES users (id),

    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT backer_segments_name_length CHECK (length(btrim(name)) BETWEEN 1 AND 80),

    -- Long enough for an email address and short enough that the column is not
    -- a place to paste a document.
    CONSTRAINT backer_segments_term_length CHECK (
        term IS NULL OR length(btrim(term)) BETWEEN 1 AND 120
    ),

    -- The five states the report covers. See the header for why it is five and
    -- not twelve, and for the test that holds this list against the enum.
    CONSTRAINT backer_segments_states_known CHECK (
        states IS NULL OR (
            cardinality(states) BETWEEN 1 AND 5
            AND states <@ ARRAY['CONFIRMED', 'CHARGE_PENDING', 'CHARGE_FAILED', 'COLLECTED', 'FULFILLED']::text[]
        )
    ),

    CONSTRAINT backer_segments_reward_tiers_present CHECK (
        reward_tier_ids IS NULL OR cardinality(reward_tier_ids) BETWEEN 1 AND 200
    ),

    -- Element-wise shape, expressed over the joined string because a CHECK
    -- cannot contain a subquery and PostgreSQL has no "every member matches"
    -- operator. The pattern is exact rather than approximate: a member of any
    -- length other than two, or one containing the separator, fails it.
    CONSTRAINT backer_segments_countries_shape CHECK (
        countries IS NULL OR (
            cardinality(countries) BETWEEN 1 AND 250
            AND array_to_string(countries, ',') ~ '^[A-Z]{2}(,[A-Z]{2})*$'
        )
    )
);

COMMENT ON TABLE backer_segments IS
    '§4.7 CD-10 (#97): a named filter over a campaign''s backers. Stores the question and never the answer — membership is re-evaluated against pledges on every read.';
COMMENT ON COLUMN backer_segments.states IS
    'NULL means every state the report covers, not none. The five members are PledgeState.ACTIVE minus DRAFT.';
COMMENT ON COLUMN backer_segments.created_by IS
    'Who saved it. Not who may use it: the segment belongs to the campaign, and every holder of VIEW_FINANCES on it may read, edit and delete it.';

-- One name per campaign, compared the way a person compares them: folded and
-- trimmed. Two segments called "Germany" and "germany " on one campaign are the
-- same segment named twice, and the second one is somebody who forgot they made
-- the first.
CREATE UNIQUE INDEX backer_segments_project_name_key
    ON backer_segments (project_id, lower(btrim(name)));

-- Listing a campaign's segments, newest first, which is the one query this table
-- answers. Covered by the unique index above for the lookup, but not for the
-- order, and a creator's segment list is read on every visit to the report.
CREATE INDEX backer_segments_project_created_idx
    ON backer_segments (project_id, created_at DESC);

-- ---------------------------------------------------------------------------
-- What the report reads
-- ---------------------------------------------------------------------------
--
-- "This campaign's backers, newest first." The order is the pair
-- `(backed_at DESC, id DESC)`, because that pair is also the keyset cursor: two
-- pledges confirmed in the same microsecond are ordered by identifier, so a page
-- boundary can never land between two rows the query considers equal and drop
-- one.
--
-- **`backed_at` is `COALESCE(confirmed_at, created_at)`**, and the fallback is
-- not decoration. Nothing in this schema requires `confirmed_at` to be set on a
-- confirmed pledge — the confirmation path sets it, and a check constraint
-- saying so belongs to the issue that owns that path — so a NULL is possible.
-- Ordering by a nullable column would sort those rows to one end and then break
-- the keyset comparison against them, because a row comparison involving NULL is
-- NULL and the page after it would be empty. A backer missing from the
-- fulfilment list is the expensive failure here; being ordered by when their
-- pledge row appeared is the cheap one.
--
-- **Partial on the five states**, which is what makes it small — a campaign's
-- expired reservations are the majority of its pledge rows and none of them is
-- in the report. The read states the same five as literals so that the planner
-- can prove the query's predicate implies this index's; a parameterised
-- `state = ANY(:states)` alone cannot be proven to and would fall back to a
-- scan. `BackerListRepository` assembles those literals from the enum for
-- exactly that reason.
CREATE INDEX pledges_backer_report_idx
    ON pledges (project_id, (COALESCE(confirmed_at, created_at)) DESC, id DESC)
    WHERE state IN ('CONFIRMED', 'CHARGE_PENDING', 'CHARGE_FAILED', 'COLLECTED', 'FULFILLED');
