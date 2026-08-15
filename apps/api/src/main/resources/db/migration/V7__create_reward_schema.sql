-- What a backer is promised: the atomic items a creator produces, the tiers
-- composed from them, and what shipping one costs where.
--
-- Four tables and one design. §4.6 makes items primary and tiers derived —
-- "atomic items first, then tiers composed from them" — because the same mug
-- appears in four tiers and its weight, its digital-or-physical nature, and its
-- stock-keeping code are properties of the mug rather than of any one tier. The
-- alternative, a free-text list of contents per tier, was rejected: it cannot
-- answer "how many mugs do I owe" at fulfilment, which is the question the
-- pledge manager exists to answer.
--
-- `reward_tiers.claimed_quantity + reserved_quantity <= limit_quantity` is a
-- database constraint rather than a Java check, and that is the point of this
-- migration. A limit enforced only in the application is oversold stock the
-- first time two checkouts race, and overselling a limited reward is a promise
-- to a backer that the creator cannot keep. Reservation itself is #51; what is
-- here are the columns and the constraint it will rely on.
--
-- Reverse:
--   DROP TABLE IF EXISTS shipping_rules;
--   DROP TABLE IF EXISTS reward_tier_items;
--   DROP TABLE IF EXISTS reward_tiers;
--   DROP TABLE IF EXISTS items;
--   -- Safe only before a campaign has taken a pledge. Afterwards a reward tier
--   -- is the description of an obligation somebody paid for, and dropping these
--   -- tables destroys the evidence of what was promised. The way back from a bad
--   -- release is the previous build of the application; this block is for a
--   -- database that has served no traffic.

-- ---------------------------------------------------------------------------
-- items
-- ---------------------------------------------------------------------------

-- §7.2. The atomic unit: one thing the creator makes, described once and
-- referenced by every tier that includes it.
CREATE TABLE items (
    id           uuid        PRIMARY KEY,
    -- Cascades, unlike the reference from projects to users. An item has no
    -- meaning without the campaign that produces it, and a campaign that can be
    -- hard-deleted is one that never launched.
    project_id   uuid        NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    name         text        NOT NULL,
    description  text,
    -- INTERIM, and the same interim as projects.cover_image_url. There is no
    -- `media` table and no uploader (§13), so §7.2's `image_id` cannot reference
    -- anything yet. The column takes a URL the client supplies; nothing on the
    -- server has seen the file, and nothing pretends to have measured it.
    --
    -- The media pipeline replaces this with `image_id` referencing `media` under
    -- expand-then-contract, exactly as planned for the cover image. Nothing
    -- outside the reward module may read it, so the contract half touches one
    -- module.
    image_url    text,
    -- Shipping is priced by weight in every carrier tariff, so the weight
    -- belongs to the item and not to the tier: a tier's weight is the sum of
    -- what is in it, which is a query rather than a column somebody maintains.
    weight_grams int,
    is_digital   boolean     NOT NULL DEFAULT false,
    -- The creator's own stock-keeping code, not ours. Optional, because a
    -- first-time creator has no coding scheme and should not be asked to invent
    -- one; unique within the campaign when present, because the entire purpose
    -- of the code is to identify one item unambiguously in a warehouse.
    sku          text,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT items_name_length CHECK (length(btrim(name)) BETWEEN 1 AND 120),
    CONSTRAINT items_description_not_blank CHECK (description IS NULL OR length(btrim(description)) > 0),
    CONSTRAINT items_sku_length CHECK (sku IS NULL OR length(btrim(sku)) BETWEEN 1 AND 64),
    -- Zero grams is not a weight, and a negative one is not anything.
    CONSTRAINT items_weight_is_positive CHECK (weight_grams IS NULL OR weight_grams > 0),
    -- A digital item has no shipping weight, and a weight recorded against one
    -- would be added into a shipping calculation for a file. Stated here rather
    -- than in the application because it is the shipping total that goes wrong,
    -- silently, months later.
    CONSTRAINT items_digital_items_have_no_weight CHECK (NOT is_digital OR weight_grams IS NULL),
    -- Lets reward_tier_items name a tier and an item in one pair of foreign keys
    -- that cannot disagree about the campaign. The same device as
    -- subcategories_identity_within_parent in V6.
    CONSTRAINT items_identity_within_project UNIQUE (id, project_id)
);

CREATE INDEX items_project_idx ON items (project_id);

-- Partial, because most items have no code and a unique index over nulls would
-- be an index of nothing.
CREATE UNIQUE INDEX items_project_sku_key ON items (project_id, sku) WHERE sku IS NOT NULL;

CREATE TRIGGER items_set_updated_at
    BEFORE UPDATE ON items
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON TABLE items IS
    'Atomic units a campaign produces. Composed into reward tiers through reward_tier_items.';
COMMENT ON COLUMN items.image_url IS
    'Interim. Replaced by image_id when the media module lands; see V7 for the plan.';

-- ---------------------------------------------------------------------------
-- reward_tiers
-- ---------------------------------------------------------------------------

-- §7.2. What a backer selects and pays for.
CREATE TABLE reward_tiers (
    id                 uuid           PRIMARY KEY,
    project_id         uuid           NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    title              text           NOT NULL,
    description        text,
    -- numeric(14,2), never a float, and BigDecimal above it. This is the number
    -- a card is charged for.
    amount             numeric(14, 2) NOT NULL,
    -- Denormalised from the campaign, which owns the currency and fixes it at
    -- creation. Repeated here because a pledge is priced from this row and a
    -- price with no currency on it is not a price; the application refuses a
    -- tier whose currency is not the campaign's.
    currency           text           NOT NULL DEFAULT 'AZN',
    -- A date rather than a timestamp: what a creator can honestly promise is a
    -- month, and a timestamp would render as an hour of delivery that nobody
    -- committed to. Stored as a date so that "which tiers are overdue" is a
    -- comparison rather than a parse.
    estimated_delivery date,
    -- Null means unlimited, which is the common case. A zero limit is not an
    -- unlimited tier, it is a tier nobody can select, so it is refused below.
    limit_quantity     int,
    -- Maintained by the pledge module (epic #50) and by reservation (#51). Read
    -- here, never written by the reward endpoints: a tier that could edit its own
    -- claimed count is a tier that can decide it still has stock.
    claimed_quantity   int            NOT NULL DEFAULT 0,
    reserved_quantity  int            NOT NULL DEFAULT 0,
    shipping_type      text           NOT NULL DEFAULT 'NONE',
    is_early_bird      boolean        NOT NULL DEFAULT false,
    is_featured        boolean        NOT NULL DEFAULT false,
    is_secret          boolean        NOT NULL DEFAULT false,
    -- The shareable half of a secret tier: the tier is absent from the public
    -- reward list and reachable only by a link carrying this.
    --
    -- Stored in the clear, unlike verification_tokens in V2, and the difference
    -- is what the token does. A verification token authenticates a person, is
    -- used once, and is never shown again — so a hash is enough and a leaked
    -- table is harmless. This one is a capability the creator distributes by
    -- hand, repeatedly, to a mailing list; hashing it would mean the creator
    -- could never read back the link they are supposed to be sending.
    secret_token       text,
    -- An add-on is sold alongside a tier rather than instead of one (§4.6), so
    -- it is a tier that does not appear in the "choose a reward" list and does
    -- not carry the pledge on its own.
    is_addon           boolean        NOT NULL DEFAULT false,
    -- Display order within the campaign, set by drag-to-reorder. Not unique:
    -- reorder rewrites every row in the campaign, and a unique constraint would
    -- refuse the intermediate states of that rewrite unless it were deferred.
    sort_order         int            NOT NULL DEFAULT 0,
    available_from     timestamptz,
    available_until    timestamptz,
    -- Optimistic locking. Two writers to one tier are ordinary here — the
    -- creator editing it while a backer's checkout reserves a place — and unlike
    -- a state transition the loser has something useful to do about it, which is
    -- re-read and retry. A pessimistic lock, which is what projects uses for
    -- transitions, would instead make a checkout wait on a creator's autosave.
    version            bigint         NOT NULL DEFAULT 0,
    created_at         timestamptz    NOT NULL DEFAULT now(),
    updated_at         timestamptz    NOT NULL DEFAULT now(),

    CONSTRAINT reward_tiers_title_length CHECK (length(btrim(title)) BETWEEN 1 AND 80),
    CONSTRAINT reward_tiers_description_not_blank CHECK (
        description IS NULL OR length(btrim(description)) > 0
    ),
    -- §5.3: a reward price is at least the smallest chargeable amount. What that
    -- amount is belongs to configuration and to the payment provider; zero and
    -- below are not prices at all, and that much can be refused here.
    CONSTRAINT reward_tiers_amount_is_positive CHECK (amount > 0),
    CONSTRAINT reward_tiers_currency_shape CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT reward_tiers_limit_is_positive CHECK (limit_quantity IS NULL OR limit_quantity > 0),
    CONSTRAINT reward_tiers_claimed_is_not_negative CHECK (claimed_quantity >= 0),
    CONSTRAINT reward_tiers_reserved_is_not_negative CHECK (reserved_quantity >= 0),
    -- **The constraint this migration exists for.** §7.2 states it and §5.3
    -- depends on it: a quantity may be increased freely and decreased only above
    -- what is already claimed.
    --
    -- In the database rather than only in Java because the failure mode is a
    -- race. Two checkouts that both read "one place left", both find it
    -- available, and both write a reservation are two backers promised the last
    -- unit — and the application code that checked is not wrong, it is merely
    -- not serialised. #51 will take a row lock and increment inside it; this is
    -- what makes a bug in that code a refused transaction rather than an
    -- oversold reward nobody notices until fulfilment.
    CONSTRAINT reward_tiers_stock_is_within_the_limit CHECK (
        limit_quantity IS NULL OR claimed_quantity + reserved_quantity <= limit_quantity
    ),
    CONSTRAINT reward_tiers_shipping_type_known CHECK (
        shipping_type IN (
            'NONE',          -- nothing is delivered: a thank-you, a credit
            'DIGITAL',       -- delivered as a file or a licence, no address
            'LOCAL_PICKUP',  -- collected in person, no carrier
            'DOMESTIC',      -- shipped within the country only
            'INTERNATIONAL'  -- shipped anywhere the creator has priced
        )
    ),
    -- A secret tier is not in the public reward list, so a token is the only way
    -- to reach it and a tier flagged secret without one is a tier nobody can
    -- select. The converse matters as much: a token on a tier that is not secret
    -- is a link a creator believes is private and is not.
    CONSTRAINT reward_tiers_secret_tiers_have_a_token CHECK (is_secret = (secret_token IS NOT NULL)),
    CONSTRAINT reward_tiers_secret_token_shape CHECK (
        secret_token IS NULL OR secret_token ~ '^[A-Za-z0-9_-]{16,64}$'
    ),
    -- Featured means shown first on the campaign page; secret means not shown at
    -- all. A row claiming both is a contradiction the page would have to resolve
    -- by guessing.
    CONSTRAINT reward_tiers_secret_tiers_are_not_featured CHECK (NOT (is_secret AND is_featured)),
    -- An early bird is early because it runs out: either it closes at a time or
    -- it is capped at a number of places. Without one of the two it is an
    -- ordinary tier with a label that misleads a backer into hurrying.
    CONSTRAINT reward_tiers_early_birds_end CHECK (
        NOT is_early_bird OR available_until IS NOT NULL OR limit_quantity IS NOT NULL
    ),
    CONSTRAINT reward_tiers_availability_window_is_ordered CHECK (
        available_from IS NULL OR available_until IS NULL OR available_until > available_from
    ),
    CONSTRAINT reward_tiers_identity_within_project UNIQUE (id, project_id)
);

-- The reward list of one campaign, in display order. Every read of this table
-- from the editor and from the campaign page is that query.
CREATE INDEX reward_tiers_project_sort_idx ON reward_tiers (project_id, sort_order);

-- Resolving a secret link. Unique because the token is what identifies the
-- tier in that request, and partial because only secret tiers have one.
CREATE UNIQUE INDEX reward_tiers_secret_token_key ON reward_tiers (secret_token) WHERE secret_token IS NOT NULL;

CREATE TRIGGER reward_tiers_set_updated_at
    BEFORE UPDATE ON reward_tiers
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON TABLE reward_tiers IS
    'What a backer selects and pays for. claimed_quantity and reserved_quantity are written by the pledge module, not by the editor.';
COMMENT ON COLUMN reward_tiers.secret_token IS
    'In the clear, deliberately: the creator has to be able to read the link they distribute. See V7.';
COMMENT ON COLUMN reward_tiers.version IS
    'Optimistic locking. Mapped as @Version so a lost update surfaces as a conflict rather than as silent overwriting.';

-- ---------------------------------------------------------------------------
-- reward_tier_items
-- ---------------------------------------------------------------------------

-- The composition: which items a tier contains, and how many of each.
--
-- `project_id` is carried here rather than derived through either parent so
-- that both foreign keys below are composite. Without it a tier from one
-- campaign could be composed from another campaign's items — a creator's
-- product appearing in a stranger's reward — and no single-column reference can
-- refuse that.
CREATE TABLE reward_tier_items (
    reward_tier_id uuid        NOT NULL,
    item_id        uuid        NOT NULL,
    project_id     uuid        NOT NULL,
    -- Two of the same mug in one tier is an ordinary reward. Zero of something
    -- is not a composition, it is a row that should have been deleted.
    quantity       int         NOT NULL DEFAULT 1,
    created_at     timestamptz NOT NULL DEFAULT now(),

    -- One row per item per tier. The quantity is the column that says "two", so
    -- two rows for one item would be a composition that has to be summed to be
    -- read, and one of the two would eventually be updated alone.
    CONSTRAINT reward_tier_items_pkey PRIMARY KEY (reward_tier_id, item_id),
    CONSTRAINT reward_tier_items_quantity_is_positive CHECK (quantity > 0),
    CONSTRAINT reward_tier_items_belong_to_the_tiers_project
        FOREIGN KEY (reward_tier_id, project_id) REFERENCES reward_tiers (id, project_id) ON DELETE CASCADE,
    -- Not a cascade, deliberately: deleting an item that a tier includes would
    -- silently change what a backer was promised. The endpoint refuses it with a
    -- 409 naming the tiers, and this is what holds against a support query, an
    -- import, and a future code path that forgets to ask.
    --
    -- `DEFERRABLE INITIALLY DEFERRED` because the check has to survive the one
    -- legitimate deletion of an item: deleting the whole campaign. That cascades
    -- to `items` and to `reward_tiers` from the same statement, and a check
    -- performed the moment the item row goes would refuse it on the strength of
    -- a referencing row the same statement is about to remove — the outcome would
    -- then depend on the order two cascade triggers happen to fire in. Deferring
    -- to commit asks the question once, when the statement has finished, which is
    -- the only point at which the answer is meaningful.
    CONSTRAINT reward_tier_items_reference_an_item_of_the_same_project
        FOREIGN KEY (item_id, project_id) REFERENCES items (id, project_id)
        ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED
);

-- "Which tiers include this item" — asked before an item is deleted, and by
-- fulfilment to total what has to be produced.
CREATE INDEX reward_tier_items_item_idx ON reward_tier_items (item_id);

COMMENT ON TABLE reward_tier_items IS
    'Composition of a tier. project_id is carried so that both foreign keys are composite and cannot cross campaigns.';

-- ---------------------------------------------------------------------------
-- shipping_rules
-- ---------------------------------------------------------------------------

-- §7.2. What shipping one tier costs, per destination.
--
-- Per tier rather than per campaign because the tiers of one campaign differ in
-- size and weight: a poster and a boxed set do not ship for the same money, and
-- a campaign-wide table would price both at whichever the creator entered.
--
-- No currency column: shipping is charged in the campaign's currency, which the
-- tier already carries, and a shipping line in a second currency would have to
-- be converted at some rate nobody has chosen.
CREATE TABLE shipping_rules (
    reward_tier_id         uuid           NOT NULL REFERENCES reward_tiers (id) ON DELETE CASCADE,
    -- ISO 3166-1 alpha-2. Not a foreign key to a countries table, because there
    -- is not one and a two-letter code is already the identifier every carrier,
    -- every address form, and every tax rule uses.
    country_code           text           NOT NULL,
    amount                 numeric(14, 2) NOT NULL,
    -- What each unit after the first costs. Zero is a legitimate answer and the
    -- default: "one flat rate however many you order" is a rule creators offer
    -- deliberately, and it is better said by a zero the creator can see than by
    -- a null the calculation has to interpret.
    additional_item_amount numeric(14, 2) NOT NULL DEFAULT 0,
    created_at             timestamptz    NOT NULL DEFAULT now(),

    CONSTRAINT shipping_rules_pkey PRIMARY KEY (reward_tier_id, country_code),
    CONSTRAINT shipping_rules_country_code_shape CHECK (country_code ~ '^[A-Z]{2}$'),
    -- Free shipping is zero. A negative rate would be a discount applied through
    -- the shipping line, which is not what this column means and would make the
    -- pledge total unexplainable to the backer.
    CONSTRAINT shipping_rules_amount_is_not_negative CHECK (amount >= 0),
    CONSTRAINT shipping_rules_additional_amount_is_not_negative CHECK (additional_item_amount >= 0)
);

COMMENT ON TABLE shipping_rules IS
    'Per-country shipping for one tier, in the campaign currency. Replaced wholesale by PUT /v1/rewards/{id}/shipping-rules.';
