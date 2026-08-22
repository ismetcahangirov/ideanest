-- §4.8's PM-11 to PM-13 (#77): shipping that varies by region and by weight, on
-- top of the per-country flat rate V7 already gives every tier.
--
-- ---------------------------------------------------------------------------
-- WHAT V7 ALREADY DOES, AND WHAT IT CANNOT
-- ---------------------------------------------------------------------------
--
-- `shipping_rules` prices one tier to one country: a flat amount for the first
-- unit and an amount for each after it. That is PM-12's "flat rates" and it is
-- the whole of what §4.5's checkout quotes from today.
--
-- Two of PM-11's three axes are missing from it:
--
--   * **Region.** A creator shipping to the European Union types twenty-seven
--     rows per tier, and adds twenty-seven more for every tier they create. The
--     rate table becomes something nobody maintains, and the failure mode is a
--     country quietly left unpriced -- which `PledgeQuote` correctly refuses,
--     so the backer sees a checkout that will not complete and the creator sees
--     nothing at all.
--   * **Weight.** Every carrier tariff on earth is priced per kilogram.
--     `items.weight_grams` has existed since V7 and nothing has ever read it.
--
-- ---------------------------------------------------------------------------
-- THREE TABLES ADDED, AND V7'S ONE LEFT ALONE
-- ---------------------------------------------------------------------------
--
-- The tempting change was to widen `shipping_rules`: make `country_code`
-- nullable, add a `zone_id`, and rebuild the primary key around whichever is
-- set. It was rejected on the rolling-deployment rule. That primary key is
-- `(reward_tier_id, country_code)`, so widening it is a drop and a recreate of
-- the key on the table the checkout quotes from, while the previous build is
-- still selecting from it with `country_code NOT NULL` in its mapping. Expand
-- and contract would need three releases to do what a separate table does in
-- one, and would leave the checkout quoting from a half-migrated rate table in
-- the middle release.
--
-- So V7's table keeps its shape and meaning -- **the rate for a named country**
-- -- and this migration adds the rate for a *group* of countries beside it. The
-- only change to an existing table is one nullable column, which is an expand
-- with nothing to contract.
--
-- ---------------------------------------------------------------------------
-- PRECEDENCE: THE MORE SPECIFIC ANSWER WINS, AND ONLY TWO ARE POSSIBLE
-- ---------------------------------------------------------------------------
--
-- A destination can now be priced twice: by name in `shipping_rules` and by
-- membership in `shipping_zone_rules`. The named rate wins, always.
--
-- That is a rule and not a tie-break. A creator who prices the EU at 12 and then
-- adds a row for Germany at 8 has said something specific about Germany, and the
-- only reading of the second row under which it means anything is that it
-- overrides the first. The reverse -- cheapest wins, or last written wins --
-- makes the rate a backer is charged depend on the order the creator happened to
-- type things in.
--
-- **A country belongs to at most one zone per campaign**, which is what keeps
-- the ambiguity to two answers rather than n. Overlapping zones would need a
-- priority column, and a priority column is a thing creators get wrong in a way
-- that costs them money on every parcel. `shipping_zone_countries` makes the
-- constraint the primary key.
--
-- ---------------------------------------------------------------------------
-- WEIGHT IS A RATE PER KILOGRAM, ADDED TO THE FLAT AMOUNT
-- ---------------------------------------------------------------------------
--
-- Not a replacement for it and not a table of weight bands. Carriers quote a
-- handling charge plus a rate by weight, so `amount + per_kilogram_amount ×
-- kilograms` is the tariff creators are reading off when they fill this in, and
-- a zero in either column removes that half of it. Bands were rejected: they are
-- a second table, they need their own overlap rule, and the discontinuity at a
-- band edge is where a creator loses money on the parcel that is ten grams over.
--
-- The weight comes from `items.weight_grams` summed over the tier's contents ×
-- their quantities, which is a query rather than a column somebody maintains --
-- V7 said so when it put the column on the item, and this is the migration that
-- makes the sentence true.
--
-- A tier whose items have no recorded weight has a weight of zero, so a
-- per-kilogram rate contributes nothing and the creator is charged only the flat
-- amount. **That is deliberately not an error**: weights are optional in V7, the
-- great majority of campaigns never fill them in, and refusing to quote would
-- turn an incomplete catalogue into a checkout nobody can complete.
-- `ShippingCalculator` says the same thing in Java, and the creator's rate
-- editor warns when a per-kilogram rate is set on a tier that weighs nothing.
--
-- ---------------------------------------------------------------------------
-- Reverse:
--   DROP TABLE IF EXISTS shipping_zone_rules;
--   DROP TABLE IF EXISTS shipping_zone_countries;
--   DROP TABLE IF EXISTS shipping_zones;
--   ALTER TABLE shipping_rules DROP COLUMN IF EXISTS per_kilogram_amount;
--   -- Reversing prices every destination that was covered by a zone at nothing
--   -- -- which `PledgeQuote` reads as unpriced and refuses, so a campaign
--   -- relying on zones stops taking pledges rather than taking them at the
--   -- wrong price. That is the safe direction, and it is still an outage.
-- ---------------------------------------------------------------------------

-- ---------------------------------------------------------------------------
-- shipping_zones
-- ---------------------------------------------------------------------------

-- PM-13's "region": a name a creator gives to a set of destinations.
--
-- Per campaign rather than platform-wide. A platform list of regions would be
-- the platform deciding that "Europe" includes Turkey, or does not, on behalf of
-- a creator whose carrier has already decided otherwise -- and the creator would
-- have no way to say so. Regions are a property of the tariff somebody
-- negotiated, so they belong to the campaign that negotiated it.
CREATE TABLE shipping_zones (
    id         uuid        PRIMARY KEY,
    project_id uuid        NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    -- What the creator calls it: "EU", "Rest of world", "Kargo daxili". Shown to
    -- the creator in the rate editor and never to a backer -- a backer is quoted
    -- an amount for their own country and has no use for the name of the group
    -- it fell into.
    name       text        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT shipping_zones_name_length CHECK (length(btrim(name)) BETWEEN 1 AND 60),

    -- So the two child tables' foreign keys are composite and a zone cannot be
    -- borrowed by another campaign's tier.
    CONSTRAINT shipping_zones_id_project_key UNIQUE (id, project_id)
);

-- Folded and trimmed, exactly as `backer_segments` names are: "EU" and "eu " are
-- one region named twice, and the second is a creator who forgot they made the
-- first. An index rather than a table constraint because the key is an
-- expression, which `UNIQUE (...)` cannot hold.
CREATE UNIQUE INDEX shipping_zones_project_name_key
    ON shipping_zones (project_id, lower(btrim(name)));

-- V10's convention.
CREATE TRIGGER shipping_zones_set_updated_at
    BEFORE UPDATE ON shipping_zones
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON TABLE shipping_zones IS
    'PM-13: a creator-named group of destinations. Per campaign, because a region is a property of the tariff the creator negotiated.';

-- ---------------------------------------------------------------------------
-- shipping_zone_countries
-- ---------------------------------------------------------------------------

-- Which destinations a zone covers.
--
-- **The primary key is `(project_id, country_code)` and not
-- `(zone_id, country_code)`**, which is the whole precedence argument made
-- structural: a country may appear in at most one zone per campaign, so
-- resolving a destination finds either one zone or none and never two that
-- disagree. Keying it by zone would permit the overlap and push the decision
-- into a priority column nobody maintains correctly.
CREATE TABLE shipping_zone_countries (
    zone_id      uuid        NOT NULL,
    project_id   uuid        NOT NULL,
    -- ISO 3166-1 alpha-2, the same vocabulary and the same check as
    -- `shipping_rules.country_code` and `pledges.shipping_country`. Not a
    -- foreign key to a countries table because there is not one -- V7 argues
    -- this at length and nothing has changed.
    country_code text        NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT shipping_zone_countries_pkey PRIMARY KEY (project_id, country_code),
    CONSTRAINT shipping_zone_countries_zone_fkey
        FOREIGN KEY (zone_id, project_id) REFERENCES shipping_zones (id, project_id) ON DELETE CASCADE,
    CONSTRAINT shipping_zone_countries_code_shape CHECK (country_code ~ '^[A-Z]{2}$')
);

-- "What is in this zone", which is how the rate editor renders one.
CREATE INDEX shipping_zone_countries_zone_idx ON shipping_zone_countries (zone_id, country_code);

COMMENT ON TABLE shipping_zone_countries IS
    'Which countries a zone covers. Keyed by (project_id, country_code) so a destination falls into at most one zone and precedence stays a two-way question.';

-- ---------------------------------------------------------------------------
-- shipping_zone_rules
-- ---------------------------------------------------------------------------

-- What shipping one tier to one zone costs. The same three amounts
-- `shipping_rules` carries, for a group of destinations instead of one.
--
-- Per tier, like the country rate and for V7's reason: the tiers of one campaign
-- differ in size and weight, and a campaign-wide table would price a poster and
-- a boxed set at whichever the creator entered.
CREATE TABLE shipping_zone_rules (
    reward_tier_id         uuid           NOT NULL,
    zone_id                uuid           NOT NULL,
    -- Carried so both references below are composite: a tier from one campaign
    -- must not be priced against another campaign's zone.
    project_id             uuid           NOT NULL,
    amount                 numeric(14, 2) NOT NULL,
    additional_item_amount numeric(14, 2) NOT NULL DEFAULT 0,
    -- PM-12's weight-based half. Added to the flat amount rather than replacing
    -- it -- see the header. Zero is the default and means "this tier does not
    -- price by weight", which is what almost every campaign means.
    per_kilogram_amount    numeric(14, 2) NOT NULL DEFAULT 0,
    created_at             timestamptz    NOT NULL DEFAULT now(),

    CONSTRAINT shipping_zone_rules_pkey PRIMARY KEY (reward_tier_id, zone_id),
    CONSTRAINT shipping_zone_rules_tier_fkey
        FOREIGN KEY (reward_tier_id, project_id) REFERENCES reward_tiers (id, project_id) ON DELETE CASCADE,
    CONSTRAINT shipping_zone_rules_zone_fkey
        FOREIGN KEY (zone_id, project_id) REFERENCES shipping_zones (id, project_id) ON DELETE CASCADE,

    -- Free shipping is zero. A negative rate is a discount applied through the
    -- shipping line, which is not what these columns mean and makes a pledge
    -- total unexplainable to the backer. V7 says the same about its two.
    CONSTRAINT shipping_zone_rules_amount_is_not_negative CHECK (amount >= 0),
    CONSTRAINT shipping_zone_rules_additional_amount_is_not_negative CHECK (additional_item_amount >= 0),
    CONSTRAINT shipping_zone_rules_weight_amount_is_not_negative CHECK (per_kilogram_amount >= 0)
);

-- Every quote resolves by tier, and the whole rate table for a tier is read at
-- once -- V7's `PUT /v1/rewards/{id}/shipping-rules` replaces it wholesale and
-- this one is edited the same way.
CREATE INDEX shipping_zone_rules_tier_idx ON shipping_zone_rules (reward_tier_id);

COMMENT ON TABLE shipping_zone_rules IS
    'PM-12/PM-13: what one tier costs to ship to one zone. A named-country rule in shipping_rules always wins over this.';

-- ---------------------------------------------------------------------------
-- shipping_rules gains the weight rate
-- ---------------------------------------------------------------------------

-- So that a named country and a zone are priced by the same three numbers. An
-- expand with nothing to contract: a NOT NULL column with a default, which
-- PostgreSQL 11 and later add without rewriting the table, and which the
-- previous build simply does not select.
ALTER TABLE shipping_rules
    ADD COLUMN per_kilogram_amount numeric(14, 2) NOT NULL DEFAULT 0;

ALTER TABLE shipping_rules
    ADD CONSTRAINT shipping_rules_weight_amount_is_not_negative CHECK (per_kilogram_amount >= 0);

COMMENT ON COLUMN shipping_rules.per_kilogram_amount IS
    'PM-12. Added to `amount`, not a replacement for it: carriers quote handling plus a rate by weight. Zero means this tier does not price by weight.';
