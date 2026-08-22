-- §4.8's PM-09, PM-10 and PM-16 (#76): what a backer buys after the campaign has
-- closed -- a better reward tier, or more add-ons -- and what they owe for it.
--
-- ---------------------------------------------------------------------------
-- Reverse:
--   DROP TABLE IF EXISTS supplement_addons;
--   DROP TABLE IF EXISTS pledge_supplements;
--   -- Costs every post-campaign purchase and every upgrade: what a backer is owed
--   -- for and, through `supplement_addons`, what has to go in their box. Neither
--   -- is recoverable from `pledges`, which is deliberately frozen at what the
--   -- campaign raised. Nothing has been charged against these rows yet -- the
--   -- collection is epic #59 -- so the reverse costs data rather than money.
-- ---------------------------------------------------------------------------
--
-- ---------------------------------------------------------------------------
-- WHY THE PLEDGE IS NOT SIMPLY RE-QUOTED
-- ---------------------------------------------------------------------------
--
-- §4.5's PL-09 already lets a backer change what they are buying, and it works by
-- re-quoting the whole pledge: `base_amount`, `addons_amount` and
-- `shipping_amount` are rewritten and `total_amount` follows. That is right while
-- the campaign is running, because nothing has been charged and the total is a
-- promise about a future charge.
--
-- After the deadline it is wrong twice over.
--
--   * **§5.1's decision was taken against those numbers.** A campaign is judged by
--     comparing what it raised against its goal at its deadline, and V29 freezes
--     that comparison onto the row. Moving `base_amount` afterwards would change
--     the amount a campaign is reported to have raised, months later, because
--     somebody bought a second mug.
--   * **The money moves separately.** The issue's own words: an additional purchase
--     is "charged as a separate transaction". The campaign's pledges are collected
--     in one batch at the close (epic #59); a purchase made in the pledge manager
--     is charged on its own, at its own time, and may fail on its own without
--     putting the original pledge at risk.
--
-- So `pledges` keeps saying what the campaign raised, and what happens afterwards
-- is recorded here.
--
-- ---------------------------------------------------------------------------
-- TWO TABLES, AND WHY THE LINES ARE NOT IN `pledge_addons`
-- ---------------------------------------------------------------------------
--
-- The obvious place for a post-campaign add-on is V18's `pledge_addons`, which is
-- keyed `(pledge_id, reward_tier_id)`. It cannot go there. A backer who bought two
-- mugs during the campaign and one after it has one row with a quantity of three,
-- and no way to say which part of it `pledges.addons_amount` paid for -- so either
-- the sum stops matching the lines, or a later reader charges for the same mug
-- twice. `supplement_addons` keeps the second purchase whole and leaves V18's
-- invariant alone: `pledges.addons_amount` is the campaign's add-ons, and nothing
-- written here is inside it.
--
-- Fulfilment therefore reads both, which is stated rather than discovered: what
-- goes in a backer's box is `pledge_addons` plus every `supplement_addons` row on
-- their pledge.
--
-- ---------------------------------------------------------------------------
-- STOCK IS NOT DUPLICATED HERE
-- ---------------------------------------------------------------------------
--
-- An add-on is a `reward_tiers` row with `is_addon` set, and its places are held in
-- `claimed_quantity` exactly as V7 and #203 describe. A post-campaign purchase
-- claims them through the same statements; these tables record what was bought and
-- what it cost, and never how many are left.

CREATE TABLE pledge_supplements (
    id                  uuid           PRIMARY KEY,
    pledge_id           uuid           NOT NULL,
    -- Denormalised from the pledge so that "what does this campaign still have to
    -- collect" is one index rather than a join, and composite-keyed below so the
    -- copy cannot name a different campaign than the pledge does.
    project_id          uuid           NOT NULL,
    -- UPGRADE moved the pledge to a better tier; ADDONS bought more things beside
    -- it. Two kinds rather than one, because they answer different questions for
    -- the creator -- how many boxes to pack, and how many of them changed contents.
    kind                text           NOT NULL,
    -- Only an upgrade has these, and it has both: the pledge's `reward_tier_id`
    -- moves, so without them nothing records what it moved from.
    from_reward_tier_id uuid,
    to_reward_tier_id   uuid,
    -- What the backer owes for this purchase, over and above their pledge. Always
    -- positive: a downgrade is a refund, which is #67's, and recording one as a
    -- negative supplement would make a collection run pay somebody by accident.
    amount              numeric(14, 2) NOT NULL,
    currency            text           NOT NULL,
    -- Epic #59. Null on every row this platform holds, because nothing collects
    -- anything yet -- and a column rather than a state, following V22's
    -- `project_updates.published_at`: a state beside a timestamp is two facts that
    -- can disagree, and the one a support script updates is never the one the reads
    -- filter on.
    collected_at        timestamptz,
    created_at          timestamptz    NOT NULL DEFAULT now(),
    CONSTRAINT pledge_supplements_kind_is_known CHECK (kind IN ('UPGRADE', 'ADDONS')),
    CONSTRAINT pledge_supplements_pledge_fkey
        FOREIGN KEY (pledge_id, project_id) REFERENCES pledges (id, project_id) ON DELETE CASCADE,
    -- An upgrade names both tiers or it is not an upgrade; an add-on purchase names
    -- neither. Stated as one constraint so that the two halves cannot drift apart.
    CONSTRAINT pledge_supplements_tiers_match_the_kind CHECK (
        (kind = 'UPGRADE') = (from_reward_tier_id IS NOT NULL AND to_reward_tier_id IS NOT NULL)
    ),
    CONSTRAINT pledge_supplements_amount_is_positive CHECK (amount > 0),
    CONSTRAINT pledge_supplements_currency_shape CHECK (currency ~ '^[A-Z]{3}$')
);

-- What one pledge owes, oldest first: the list a backer is shown.
CREATE INDEX pledge_supplements_pledge_idx ON pledge_supplements (pledge_id, created_at);

-- "What has this campaign still to collect", which is the read epic #59 will make
-- of this table and the only one that is not per pledge. Partial, because a
-- collected supplement is never the answer.
CREATE INDEX pledge_supplements_project_uncollected_idx
    ON pledge_supplements (project_id)
    WHERE collected_at IS NULL;

COMMENT ON TABLE pledge_supplements IS
    'PM-09, PM-10, PM-16 (#76): a purchase made after the campaign closed, charged separately from the pledge.';

CREATE TABLE supplement_addons (
    supplement_id  uuid    NOT NULL REFERENCES pledge_supplements (id) ON DELETE CASCADE,
    -- No reference to `reward_tiers`: V18's `pledge_addons` has a composite one
    -- through the pledge's campaign, and the same is not available here without
    -- carrying the campaign on every line. The service resolves the tier against
    -- the campaign before it writes, and the supplement's own foreign key is what
    -- ties the purchase to a pledge that exists.
    reward_tier_id uuid    NOT NULL,
    quantity       integer NOT NULL,
    CONSTRAINT supplement_addons_pkey PRIMARY KEY (supplement_id, reward_tier_id),
    -- At least one, exactly as V18's `pledge_addons_quantity_is_positive`: a line
    -- for none of something is a line the client should have left out.
    CONSTRAINT supplement_addons_quantity_is_positive CHECK (quantity >= 1)
);

COMMENT ON TABLE supplement_addons IS
    'What one post-campaign purchase bought. Separate from pledge_addons so pledges.addons_amount keeps matching its own lines.';
