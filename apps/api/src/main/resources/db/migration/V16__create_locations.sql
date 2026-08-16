-- Where a campaign is, and the arithmetic for "near me" (#47). §4.3's Location
-- filter -- "country, city, or proximity" -- its Near me sort, and §4.4's
-- location link in the project header.
--
-- V6 left `location_id` and `geo_point` off `projects` and said exactly why:
--
--     `location_id` and `geo_point` -- proximity search. A geography column
--     also means PostGIS, and enabling an extension for a feature nobody has
--     built is a migration that can only be justified twice.
--
-- This is the second of those two justifications, and the extension is not
-- PostGIS. The reasoning is the first section below, at length, because it is
-- the decision this migration exists to take.
--
-- Nothing here removes or narrows anything: two extensions, two new tables,
-- one nullable column on `projects`, and one index. Both halves of a rolling
-- deployment are therefore safe -- the previous release does not know any of it
-- exists, `projects.location_id` is nullable so the previous release's INSERTs
-- still succeed, and nothing outside the discovery module reads it. This is the
-- EXPAND half; the contract half is described on `projects.location_id` below,
-- and its precondition is a write path that does not exist yet.
--
-- Reverse:
--   ALTER TABLE projects DROP COLUMN IF EXISTS location_id;
--   DROP INDEX IF EXISTS projects_discovery_location_idx;
--   DROP TABLE IF EXISTS location_translations;
--   DROP TABLE IF EXISTS locations;
--   DROP EXTENSION IF EXISTS earthdistance;
--   DROP EXTENSION IF EXISTS cube;
--   Reversing loses every campaign's location -- which is a creator's statement
--   about their own campaign, not seed data -- and makes `sort=near_me`,
--   `country`, `city` and `near` unanswerable, so they would have to be refused
--   again through DiscoveryCapability, which is the previous build of the
--   application. As everywhere else in this directory, the way back from a bad
--   release is that build, and this block is here because it is genuinely the
--   whole of the undo.

-- ---------------------------------------------------------------------------
-- THE GEOSPATIAL DECISION: cube + earthdistance, NOT PostGIS
-- ---------------------------------------------------------------------------
--
-- Three options were considered. All three can answer "campaigns within N km,
-- nearest first"; they differ in what they cost to run and in what they make
-- possible later.
--
--   1. PostGIS. `geography(Point, 4326)`, `ST_DWithin`, `ST_Distance`, a GiST
--      index, spheroid-accurate distance to the millimetre, and polygons,
--      projections, and topology besides. It is also the largest operational
--      dependency the platform would take on: GEOS, PROJ and GDAL as shared
--      libraries in the database image; an extension whose version is coupled to
--      the server's and which must be upgraded with `ALTER EXTENSION postgis
--      UPDATE` on a schedule of its own; and the well-known constraint that it
--      has to be present at the same version on both sides of a `pg_upgrade`.
--      §14 puts a managed PostgreSQL under this service; PostGIS turns "which
--      Postgres" into "which Postgres, with which PostGIS", on every environment
--      and in every restore rehearsal (§17.4).
--
--   2. cube + earthdistance. Both are contrib -- the same place `pg_trgm`,
--      `pgcrypto` and `citext` come from, all three of which V1 already depends
--      on, so this adds no new package to any image the platform already runs.
--      `earth_distance(ll_to_earth(a), ll_to_earth(b))` is great-circle distance
--      in metres over a sphere, and `earth_box` plus a GiST index over
--      `ll_to_earth(...)` is the bounded-radius probe.
--
--   3. Plain numeric latitude and longitude with a bounding-box prefilter and a
--      hand-written haversine. No extension at all -- and no help either: the
--      trigonometric functions are `double precision` only, so the haversine
--      would be float arithmetic that this module then has to round back into
--      `numeric` anyway, and the index strategy and the correctness of the
--      formula would both be ours to own and ours to get wrong. It buys nothing
--      over option 2 that option 2 does not already have, because option 2's
--      arithmetic is the same arithmetic, written once, in C, by somebody else.
--
-- OPTION 2, and the deciding argument is scale rather than taste.
--
-- §11.1 puts tier 1 at "up to roughly ten thousand projects". More important
-- than that number is the shape of the data below: a location is SHARED
-- REFERENCE DATA, so the number of distinct points the platform measures
-- distance to is the size of a city gazetteer -- eighteen rows today, a few
-- thousand if it ever covered the region -- and NOT the number of campaigns.
-- Every question §4.3 asks resolves against that small set:
--
--     "which locations are within N km of here"    -- over `locations`
--     "which campaigns are in one of those"        -- over `projects.location_id`
--
-- The second is an ordinary integer-ish index lookup and is what
-- `projects_discovery_location_idx` below serves. The first is arithmetic over a
-- table small enough that a sequential scan of it is free. PostGIS's real
-- advantages -- spheroid accuracy, spatial joins over millions of geometries,
-- polygon containment -- are advantages this query shape cannot spend.
--
-- ACCURACY, WHICH IS THE ONE THING WORTH CHECKING. `earth_distance` measures on
-- a sphere of radius 6378168 m (`earthdistance`'s `earth()`), which is the WGS84
-- equatorial radius rather than the mean; against a proper spheroid the error is
-- under about 0.5% anywhere, and roughly +0.2% at Azerbaijani latitudes. On a
-- 100 km radius control that is 200 m, on a point that is a city centroid
-- standing for a whole city. `DiscoveryProximityTests` pins it against two known
-- real distances -- Baku to Ganja and Baku to Istanbul -- rather than against
-- itself, because a formula that is wrong by a factor of pi/180 also agrees with
-- itself perfectly.
--
-- WHAT MAKES THIS REVERSIBLE, AND IT IS THE POINT. The coordinates below are
-- stored as plain `numeric` degrees, not as an extension type. `earth_distance`
-- is called ON them, never stored. So the extension is an implementation of one
-- function over two ordinary columns, and replacing it with PostGIS later is one
-- migration -- add `geography`, backfill from `latitude`/`longitude`, swap the
-- expression in `PostgresSearchService` -- with no data conversion and nothing to
-- reconstruct. Had the point been stored as a `geography` from the start, the
-- reverse direction would not be true.
--
-- WHAT WOULD CHANGE THE DECISION LATER, named so nobody has to guess:
--
--   * A second geospatial consumer that needs polygons. Shipping rules by
--     administrative region (§7.2 `shipping_rules`), "draw a region on a map",
--     or delivery zones are all polygon containment, which earthdistance cannot
--     express at all. One of those is the day PostGIS becomes the cheaper answer.
--   * Per-campaign coordinates rather than per-location. If a campaign ever
--     carries its own point -- a venue, a studio address -- the distinct-point
--     count becomes the campaign count, the argument above stops holding, and an
--     index that the planner will actually use becomes necessary rather than
--     hypothetical. See the note on the index below.
--   * §11.1's tier 2. A dedicated search engine has geo primitives of its own
--     and none of this SQL survives the move; what survives is `DiscoveryQuery`,
--     `LocationFilter`, the wire parameters, and the cursor encoding, which is
--     exactly what the §11.1 seam exists to make true.

-- The extension `earthdistance` depends on. Enabled explicitly rather than
-- relied on as a transitive install, so that reading this file tells you both of
-- the things that were switched on.
CREATE EXTENSION IF NOT EXISTS cube;

-- Great-circle distance between two points on the earth, in metres, and the
-- bounding box that makes a radius indexable. See the section above for why this
-- and not PostGIS.
CREATE EXTENSION IF NOT EXISTS earthdistance;

-- ---------------------------------------------------------------------------
-- locations
-- ---------------------------------------------------------------------------

-- WHY A TABLE RATHER THAN A COLUMN ON `projects`. §4.3 puts a live count beside
-- every filter value (D-10) and §4.4 makes the location on a project page a
-- link. Both require that two campaigns in Baku are in the SAME Baku. Free text
-- on `projects` cannot promise that: "Baku", "Bakı", "BAKU", "Baki city" and a
-- trailing space are five cities in a facet panel and one city in the world, and
-- the panel is where the reader finds out. So a location is reference data, a
-- campaign points at a row, and the vocabulary is closed.
--
-- WHO CREATES A ROW. Nobody, at run time, today. There is no endpoint that
-- writes to this table and no endpoint that sets `projects.location_id`; the
-- rows below are the whole of the data, and a campaign acquires a location by
-- being pointed at one of them. That is deliberate rather than unfinished: a
-- creator typing a place name is exactly the free-text failure this table
-- exists to prevent, so the write path is a picker over this list, which belongs
-- to the campaign editor and not to this issue. When it arrives, ADDING a
-- location is a privileged action -- it changes a closed vocabulary every reader
-- sees and every facet counts -- and CLAUDE.md requires it to be audited; the
-- shape to copy is `curation_events`, which is `project_state_transitions`'.
-- Nothing is audited here because nothing here is an action somebody took.
CREATE TABLE locations (
    id           uuid        PRIMARY KEY,
    -- The stable handle, and what `?city=` matches against. It is the FOLDED
    -- comparable form of the name -- §11.3's fold, the same one `tags.slug` and
    -- `Slugs.slugify` produce -- which is what makes `?city=Bakı`, `?city=Baku`
    -- and `?city=baki` one filter rather than three. Globally unique rather than
    -- unique within a country: it is a single URL-facing token, and two places
    -- that fold to one slug are disambiguated when the second is added, in the
    -- migration that adds it, rather than by a compound key every caller would
    -- then have to send both halves of.
    slug         text        NOT NULL,
    -- ISO 3166-1 alpha-2, upper case. A code rather than a name because a name
    -- is language-dependent and a code is not, and because every client platform
    -- already ships a localised country-name table (`Intl.DisplayNames`) that
    -- this service has no business duplicating in four languages -- §21.1 says
    -- to use the platform internationalisation APIs, and this is one.
    country_code text        NOT NULL,
    -- Degrees, as `numeric`, with the scale chosen for what a city centroid is
    -- and NOT for what a coordinate can be.
    --
    -- FOUR DECIMAL PLACES IS ABOUT 11 METRES, AND THE PRECISION IS A PRIVACY
    -- DECISION RATHER THAN A STORAGE ONE. These points stand for whole cities;
    -- six places would be a tenth of a metre, which is a doorstep. Bounding the
    -- scale in the column type means the table CANNOT hold somebody's address
    -- even if a future write path tried to put one there -- a constraint is a
    -- better promise than a convention, and §17.4's position is that personal
    -- data the platform does not need is data it does not keep. The API applies
    -- the matching rule to the origin a caller sends: see `LocationFilter`,
    -- which quantises an incoming point to two places before it reaches a query,
    -- a fingerprint, a cursor, or a log.
    --
    -- `numeric` rather than `double precision` for the reason every other number
    -- in this module is `numeric`: the distance derived from these is the keyset
    -- cursor's sort key, and a key that two evaluations round differently makes
    -- a scroll skip a row.
    latitude     numeric(6, 4) NOT NULL,
    longitude    numeric(7, 4) NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT locations_slug_shape CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    CONSTRAINT locations_slug_length CHECK (length(slug) BETWEEN 2 AND 60),
    -- Two upper-case letters. `AZ`, not `az` and not `AZE`: one spelling, so a
    -- filter cannot miss a campaign because the row was written in the other.
    CONSTRAINT locations_country_code_shape CHECK (country_code ~ '^[A-Z]{2}$'),
    -- The poles and the antimeridian are inside these bounds rather than outside
    -- them, deliberately: latitude is closed at ±90 and longitude at ±180. A
    -- point at exactly 180° is a real place and `ll_to_earth` handles it; see
    -- `DiscoveryProximityTests`, which measures across the antimeridian and over
    -- a pole rather than asserting that the case cannot arise.
    CONSTRAINT locations_latitude_in_range CHECK (latitude BETWEEN -90 AND 90),
    CONSTRAINT locations_longitude_in_range CHECK (longitude BETWEEN -180 AND 180)
);

CREATE UNIQUE INDEX locations_slug_key ON locations (slug);

-- "Every city in this country", which is what the country facet groups by and
-- what a country filter resolves to before it touches `projects`.
CREATE INDEX locations_country_idx ON locations (country_code, slug);

CREATE TRIGGER locations_set_updated_at
    BEFORE UPDATE ON locations
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON TABLE locations IS
    'Shared reference data: the places a campaign can be. Closed vocabulary, so two campaigns in Baku '
    'are in the same Baku and the city facet counts one city rather than five spellings.';
COMMENT ON COLUMN locations.latitude IS
    'Degrees, four decimal places (~11 m). The scale is a bound, not a format: these are city centroids '
    'and the column must not be able to hold an address.';

-- ---------------------------------------------------------------------------
-- WHAT IS DELIBERATELY NOT HERE: A GiST INDEX ON ll_to_earth(latitude, longitude)
-- ---------------------------------------------------------------------------
--
-- The obvious index for this feature is
--
--     CREATE INDEX locations_geo_idx ON locations USING gist (ll_to_earth(latitude, longitude));
--
-- and it is not here because THE PLANNER DOES NOT USE IT. Measured on
-- postgres:16-alpine, with the index created and ANALYZE run, against the
-- eighteen rows below:
--
--     EXPLAIN (ANALYZE, BUFFERS) SELECT lf.id FROM locations lf
--      WHERE earth_box(ll_to_earth(40.41::float8, 49.87::float8), 300000)
--            @> ll_to_earth(lf.latitude::float8, lf.longitude::float8);
--
--     Seq Scan on locations lf (cost=0.00..5.82 rows=1 width=16)
--                              (actual time=0.095..0.191 rows=17 loops=1)
--       Buffers: shared hit=1
--     Execution Time: 0.229 ms
--
-- One page, and the index is not touched. Forced with `SET enable_seqscan =
-- off`, to show it is a working index rather than an unusable one:
--
--     Index Scan using locations_geo_idx on locations lf
--                              (cost=0.13..8.15 rows=1 width=16)
--                              (actual time=0.093..0.095 rows=17 loops=1)
--       Buffers: shared hit=2
--     Execution Time: 0.175 ms
--
-- Twice the buffers for a table that fits in one page, and a plan the planner
-- costs higher than the scan. So the index would be write amplification on a
-- table nothing writes to and, worse, it would LOOK LIKE COVERAGE: somebody
-- reading this file would believe the radius filter was index-served when it is
-- not. #42 removed a covering index from V12 for exactly this reason and wrote
-- down why; this is the same judgement, taken before the index shipped rather
-- than after.
--
-- (Note also that the probe returns seventeen of eighteen rows. A 300 km circle
-- over a country 500 km across is most of the gazetteer, which is the other half
-- of why an index is beside the point at this scale: the filter is not
-- selective, and an index that returns nearly every row is a slower sequential
-- scan.)
--
-- THE CONDITION UNDER WHICH IT BECOMES THE RIGHT INDEX is a bigger gazetteer:
-- a real world city list is order 10^5 rows, the radius stops selecting most of
-- it, the containment probe wins, and this comment becomes a one-line migration.
-- The second condition is the one named at the top of this file -- a per-campaign
-- point rather than a per-location one -- at which the geometry moves onto
-- `projects` and the index moves with it.
--
-- WHAT THE NEAR-ME FEED ACTUALLY COSTS, measured the same way against 20,000
-- publicly visible campaigns -- twice §11.1's tier-1 ceiling, the same order #44
-- used -- 13,334 of them with a location and 6,666 without, after VACUUM ANALYZE
-- and with the cache warm. EXPLAIN (ANALYZE, BUFFERS), first page, limit 26:
--
--   sort=near_me, no filter              19.7 ms   1821 shared hits, 0 read
--   sort=near_me, second page (keyset)   20.4 ms   1821 shared hits
--   sort=near_me, radiusKm=100           10.3 ms   1822 shared hits
--   sort=near_me, category=games          3.6 ms   1339 shared hits
--   sort=near_me, city=baki               2.7 ms   1118 shared hits
--   ?country=AZ, sort=newest              0.15 ms     62 shared hits
--   ?city=baki,  sort=newest              0.36 ms    446 shared hits
--   sort=newest    (for comparison)       0.07 ms     29 shared hits
--   sort=popularity (for comparison)     28.6 ms   1863 shared hits
--
-- (#44's own comment reports relevance at 62 ms and popularity at 138 ms. Those
-- were a different run on different hardware -- popularity measures 28.6 ms
-- here -- so the column above re-measures the comparators rather than quoting
-- them, and the ratio is what carries over: near-me costs about the same as the
-- cheapest of the three scoring sorts, and about a third of relevance.)
--
-- Two things in those numbers are this design working.
--
--   * NEAR-ME IS CHEAPER THAN THE POPULARITY SORT ALREADY SHIPPED, because the
--     eighteen distances are computed once in a MATERIALIZED CTE over
--     `locations` and hash-joined, rather than once per campaign. See
--     PostgresSearchService.LOCATION_DISTANCE_CTE, which has the before-and-after:
--     the inline form measured 118.7 ms on page one and 199.8 ms on page two.
--   * THE COUNTRY AND CITY FILTERS ARE INDEX-SERVED AND ARE THE CHEAPEST
--     FILTERED QUERIES IN THE MODULE, because they reduce to a membership test
--     over `projects.location_id` after a table of eighteen rows has been read.
--     `?country=AZ` plans as an index scan on V12's
--     `projects_discovery_newest_idx` with the location probe as an inner
--     nested-loop lookup on `locations_pkey`; 62 buffers for a first page, which
--     is twice the unfiltered newest feed and a two-hundredth of any sort.
--
-- A SEEDED DATABASE IS NOT A LOAD TEST (#141). These are single queries on an
-- idle container with a warm cache. They say the shape is right and the constant
-- is small; they say nothing about §20's thousand requests a second, about
-- concurrency, about what the plan does when `projects` no longer fits in shared
-- buffers, or about what parallel workers cost when a hundred requests arrive at
-- once. What they do establish is the ceiling §11.1 already names: the unbounded
-- near-me feed is linear in publicly visible campaigns, no index removes that,
-- and the step after it is tier 2 rather than another CREATE INDEX.
--
-- What IS indexed is the join that is actually large: see
-- `projects_discovery_location_idx` below.

-- ---------------------------------------------------------------------------
-- location_translations
-- ---------------------------------------------------------------------------

-- WHETHER A PLACE NAME IS TRANSLATED THE WAY V11 TRANSLATES THE TAXONOMY, AND
-- WHY THE ANSWER IS "ALMOST".
--
-- The objection is real and worth stating before the decision: a city's endonym
-- is not a translation. "Bakı" is what the place is called; "Baku", "Баку" and
-- "Bakü" are exonyms and transliterations, not renderings of a key the platform
-- invented. A category name is genuinely interface text -- somebody chose the
-- word "Oyunlar" for a bucket the platform made up -- and a city name is not.
--
-- It is still one row per place per locale, for two reasons that outweigh it.
--
--   1. THE ALTERNATIVE IS WORSE IN THE SAME WAY V11 SAID IT WAS. A `name_az` and
--      `name_en` pair on `locations` is precisely the shape V11 exists to remove:
--      shipping Russian would be a schema change plus a deployment rather than an
--      INSERT, and §21.1 ships four languages. A single `name` column in the
--      endonym would put "Bakı" in an English facet panel and "Naxçıvan" in a
--      Russian one, which is not a defensible answer either.
--   2. THE `az` ROW IS THE ENDONYM AND IS AUTHORITATIVE. The fallback chain is
--      requested locale, then `az`, then the slug -- the same chain `Taxonomy`
--      resolves a category through. So a locale with no row falls back to what
--      the place actually calls itself rather than to English, which is the
--      correct default for a proper noun and is the opposite of what a
--      translation table normally does.
--
-- Every location below therefore has an `az` row, which is the endonym, and an
-- `en` row, which is the conventional English exonym. Russian and Turkish are
-- deliberately absent rather than guessed: they arrive as data, which is the
-- whole point of the table, and inventing a transliteration of eighteen place
-- names in a migration would be the platform asserting an authority over what
-- somewhere is called that it does not have.
--
-- No surrogate key, for the reason V11 gives on `category_translations`: the
-- identity of a name is the place and the language, and an id would only give
-- the table a second way to say that plus a way to write two rows for one pair.
CREATE TABLE location_translations (
    location_id uuid        NOT NULL REFERENCES locations (id) ON DELETE CASCADE,
    -- The four codes of §21.1, as bare language codes rather than BCP 47 tags,
    -- exactly as V11 and V14 spell them.
    locale      text        NOT NULL,
    name        text        NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),

    PRIMARY KEY (location_id, locale),
    CONSTRAINT location_translations_locale_known CHECK (locale IN ('az', 'en', 'ru', 'tr')),
    CONSTRAINT location_translations_name_not_blank CHECK (length(btrim(name)) > 0)
);

CREATE TRIGGER location_translations_set_updated_at
    BEFORE UPDATE ON location_translations
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON TABLE location_translations IS
    'One name per place per locale. The az row is the endonym and is mandatory; the others are exonyms '
    'and are optional, because the fallback for a proper noun is what the place calls itself.';

-- ---------------------------------------------------------------------------
-- projects.location_id
-- ---------------------------------------------------------------------------

-- NULLABLE, AND IT STAYS NULLABLE IN THIS MIGRATION. Every campaign that exists
-- has no location, so a NOT NULL column would fail on the ALTER and a NOT NULL
-- with a default would invent a fact about somebody's campaign. §5.3's
-- submission requirements are the completeness checklist's business (#37) and
-- not this migration's: a schema constraint refuses to create the DRAFT the
-- editor starts from, which is the wrong end of the campaign to refuse at.
--
-- THE CONTRACT HALF, and what has to be true first. Making this NOT NULL would
-- require, in order: a write path that sets it (the editor's location picker);
-- §5.3 gaining "a location" as a submission requirement, which is a product
-- decision rather than a schema one; and a backfill for every campaign that
-- launched before both. Until all three, the honest state is a nullable column,
-- and discovery is written for it -- a campaign with no location sorts last
-- under `near_me` and is excluded by a radius, and appears untouched under every
-- other sort. See `DiscoverySort.NEAR_ME`.
--
-- No ON DELETE clause, and the omission is the point: with none, PostgreSQL
-- refuses to delete a location that campaigns still point at. A cascade would
-- delete campaigns because somebody tidied a gazetteer, and a SET NULL would
-- silently unfile them. Reference data is retired by stopping offering it, not
-- by deleting the row underneath the rows that reference it.
ALTER TABLE projects ADD COLUMN location_id uuid REFERENCES locations (id);

COMMENT ON COLUMN projects.location_id IS
    'Where the campaign is (§4.3, §4.4). Nullable: no write path sets it yet, and a location is not a '
    'submission requirement in §5.3. NOT NULL is the contract half and needs the editor first.';

-- Serves: `?country=`, `?city=`, `?near=`, and `sort=near_me`, all four of which
-- reduce to `p.location_id IN (<a handful of ids>)` after the small `locations`
-- table has been read. Partial over the nine publicly visible states of §4.3,
-- for the reason V12 gives at length: that predicate is on every query this
-- module issues, so a partial index over exactly those nine is not an
-- optimisation of a common case but an index over the whole of what discovery
-- can ever read -- and it excludes every DRAFT on the platform, which is the
-- majority of the table on every crowdfunding platform there has ever been.
--
-- The nine states are written out rather than derived, matching V12 and
-- `projects_state_known`: a tenth state has to be a deliberate decision here too.
--
-- No `id` tiebreaker column on this one, unlike V12's four. Those are keyset
-- ORDER BY indexes and have to match the ordering exactly; this one is a
-- membership predicate whose rows are then sorted by distance, which is an
-- expression over another table and is not indexable here at all. Adding `id`
-- would be a wider index serving nothing.
--
-- AND IT IS ACTUALLY USED, which the GiST index above is not and which is the
-- whole reason that one is a comment and this one is an index. Measured on the
-- same 20,000-campaign database, `sort=near_me&city=baki`, warm:
--
--     ->  Bitmap Heap Scan on projects p (actual time=0.259..0.923 rows=1112)
--           Buffers: shared hit=1114
--           ->  Bitmap Index Scan on projects_discovery_location_idx
--                 (cost=0.00..16.62 rows=1111) (actual time=0.162..0.162 rows=1112)
--                 Buffers: shared hit=2
--     Execution Time: 2.689 ms
--
-- Two buffers to find 1,112 campaigns out of 20,000, and a first page in 2.7 ms
-- against 19.7 ms for the unfiltered near-me feed.
--
-- WHERE IT IS NOT USED, said plainly rather than left for somebody to discover:
-- under `sort=newest` or `sort=most_funded` with a location filter, the planner
-- prefers V12's keyset index and probes `locations` per row instead --
-- `?city=baki&sort=newest` measures 0.36 ms that way. That is the correct choice,
-- not a missed one: those orders are index-served and the LIMIT stops the scan
-- after a few hundred rows, so a bitmap over a thousand rows would be more work.
-- The index earns its place on the orders that have no ordering index -- near-me
-- and popularity -- which are exactly the ones that would otherwise scan the
-- whole table.
CREATE INDEX projects_discovery_location_idx
    ON projects (location_id)
    WHERE state IN (
        'PRELAUNCH', 'LIVE', 'CANCELED', 'SUCCESSFUL', 'UNSUCCESSFUL',
        'COLLECTING', 'LATE_PLEDGE', 'FULFILLING', 'COMPLETED'
    );

COMMENT ON INDEX projects_discovery_location_idx IS
    'Country, city and proximity filters for GET /v1/discover. Partial over the nine publicly visible states.';

-- ---------------------------------------------------------------------------
-- The seed
-- ---------------------------------------------------------------------------

-- EIGHTEEN AZERBAIJANI CITIES, AND NOTHING ELSE.
--
-- WHERE THE COORDINATES CAME FROM: the published city centroids for each place,
-- rounded to four decimal places -- the coordinates carried in the geographic
-- infobox of each city's article and in the open GeoNames gazetteer, which agree
-- to within a few hundred metres of each other and are the same numbers a map
-- centres on. They are centroids, not addresses: "Bakı" is one point standing
-- for a city of two million people, which is the precision this feature has and
-- the precision it claims. They are spot-checked against two known real
-- distances in `DiscoveryProximityTests` rather than trusted.
--
-- WHAT IS DELIBERATELY NOT SEEDED: a world gazetteer. There is no defensible
-- source for one here, nobody has reviewed it, and a fabricated list of ten
-- thousand places would be data the platform cannot stand behind appearing in a
-- filter panel as though it could. §21.2 puts the platform in one market, and
-- eighteen cities are the ones a campaign on it can currently be in. A second
-- country arrives when a campaign is in one -- as a migration, with a source.
--
-- The list is the eighteen largest and most administratively significant cities,
-- which between them cover every region a campaign is likely to name. It is not
-- exhaustive and does not pretend to be.
INSERT INTO locations (id, slug, country_code, latitude, longitude) VALUES
    (gen_random_uuid(), 'baki',       'AZ', 40.4093, 49.8671),
    (gen_random_uuid(), 'gence',      'AZ', 40.6828, 46.3606),
    (gen_random_uuid(), 'sumqayit',   'AZ', 40.5897, 49.6686),
    (gen_random_uuid(), 'mingecevir', 'AZ', 40.7700, 47.0489),
    (gen_random_uuid(), 'naxcivan',   'AZ', 39.2089, 45.4122),
    (gen_random_uuid(), 'seki',       'AZ', 41.1919, 47.1706),
    (gen_random_uuid(), 'lenkeran',   'AZ', 38.7529, 48.8475),
    (gen_random_uuid(), 'sirvan',     'AZ', 39.9319, 48.9208),
    (gen_random_uuid(), 'yevlax',     'AZ', 40.6172, 47.1500),
    (gen_random_uuid(), 'xankendi',   'AZ', 39.8153, 46.7522),
    (gen_random_uuid(), 'quba',       'AZ', 41.3606, 48.5128),
    (gen_random_uuid(), 'qebele',     'AZ', 40.9814, 47.8464),
    (gen_random_uuid(), 'samaxi',     'AZ', 40.6319, 48.6414),
    (gen_random_uuid(), 'xacmaz',     'AZ', 41.4589, 48.8022),
    (gen_random_uuid(), 'astara',     'AZ', 38.4558, 48.8725),
    (gen_random_uuid(), 'berde',      'AZ', 40.3744, 47.1264),
    (gen_random_uuid(), 'salyan',     'AZ', 39.5961, 48.9800),
    (gen_random_uuid(), 'susa',       'AZ', 39.7597, 46.7464);

-- The endonym for every place, which is also the mandatory `az` row.
INSERT INTO location_translations (location_id, locale, name)
SELECT l.id, 'az', n.name
  FROM locations l
  JOIN (VALUES
        ('baki',       'Bakı'),
        ('gence',      'Gəncə'),
        ('sumqayit',   'Sumqayıt'),
        ('mingecevir', 'Mingəçevir'),
        ('naxcivan',   'Naxçıvan'),
        ('seki',       'Şəki'),
        ('lenkeran',   'Lənkəran'),
        ('sirvan',     'Şirvan'),
        ('yevlax',     'Yevlax'),
        ('xankendi',   'Xankəndi'),
        ('quba',       'Quba'),
        ('qebele',     'Qəbələ'),
        ('samaxi',     'Şamaxı'),
        ('xacmaz',     'Xaçmaz'),
        ('astara',     'Astara'),
        ('berde',      'Bərdə'),
        ('salyan',     'Salyan'),
        ('susa',       'Şuşa')
       ) AS n (slug, name) ON n.slug = l.slug;

-- The conventional English exonym, which for several of these is the same word.
INSERT INTO location_translations (location_id, locale, name)
SELECT l.id, 'en', n.name
  FROM locations l
  JOIN (VALUES
        ('baki',       'Baku'),
        ('gence',      'Ganja'),
        ('sumqayit',   'Sumgait'),
        ('mingecevir', 'Mingachevir'),
        ('naxcivan',   'Nakhchivan'),
        ('seki',       'Shaki'),
        ('lenkeran',   'Lankaran'),
        ('sirvan',     'Shirvan'),
        ('yevlax',     'Yevlakh'),
        ('xankendi',   'Khankendi'),
        ('quba',       'Quba'),
        ('qebele',     'Gabala'),
        ('samaxi',     'Shamakhi'),
        ('xacmaz',     'Khachmaz'),
        ('astara',     'Astara'),
        ('berde',      'Barda'),
        ('salyan',     'Salyan'),
        ('susa',       'Shusha')
       ) AS n (slug, name) ON n.slug = l.slug;
