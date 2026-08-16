-- What a backer commits to, and the reservation that holds a limited reward's
-- last place while they enter their card details (#51).
--
-- One table. §7.2 lists `pledges` and this is that list, plus the two columns
-- reservation needs: `reservation_expires_at`, which is the time bound, and
-- `version`, which is the optimistic lock.
--
-- ---------------------------------------------------------------------------
-- THE RESERVATION IS A DRAFT PLEDGE, IN POSTGRESQL, NOT A KEY IN REDIS
-- ---------------------------------------------------------------------------
--
-- §4.5's capability table says of PL-13, "Stock reservation — Redis TTL, guards
-- against races". This migration does not do that, and the deviation is stated
-- here rather than discovered later.
--
-- §7.2 is the authority, and it is explicit about the mechanism:
--
--     Reservation (#51) increments `reserved_quantity` under a row lock and
--     relies on this constraint refusing the transaction when it gets that
--     wrong.
--
-- and V7 built `reward_tiers_stock_is_within_the_limit` for exactly that. Three
-- things follow, and together they decide it.
--
--   1. A Redis key and `reward_tiers.reserved_quantity` would be two records of
--      one fact, in two systems, with no transaction across them. The failure is
--      not hypothetical: the process that sets the key and then commits the row
--      can die between the two, and whichever half survives is now lying. What
--      makes a reservation correct is that taking the place and recording who
--      took it either both happen or neither does, and that is one transaction
--      in one database or it is a reconciliation job nobody wrote.
--
--   2. The constraint that refuses an oversold tier lives in PostgreSQL. A
--      reservation held elsewhere is a reservation that constraint cannot see,
--      so the guarantee V7 exists to give would apply to half the stock.
--
--   3. There is no Redis in `apps/api/build.gradle.kts` and none in §14.4's
--      running set for this service. Adding one is a stack decision with an
--      operational cost — another thing to run, monitor, fail over, and secure —
--      and it is not this issue's to take.
--
-- What Redis would buy is expiry without a sweep. PostgreSQL has no TTL, so the
-- lapse is `reservation_expires_at` plus §8.4's `reservation-cleaner` running
-- every minute. That is the whole cost, it is a job §8.4 already lists, and it
-- buys something back: an expired reservation is a row with a state and a
-- history rather than a key that silently stopped existing.
--
-- **And the reservation is a DRAFT pledge rather than a table of its own.**
-- §6.2's state machine already says so — `[*] --> DRAFT` and
-- `DRAFT --> EXPIRED: reservation TTL` — so a separate `reward_reservations`
-- table would be a second row describing the same intent, joined to this one,
-- and every question ("does this backer hold a place", "what is in the
-- checkout") would have to ask both and reconcile the answer. The draft is the
-- reservation.
--
-- ---------------------------------------------------------------------------
-- Rolling deployment
-- ---------------------------------------------------------------------------
--
-- One new table, referenced by nothing, read by nothing in the previous
-- release. Both halves of a rolling deploy are safe: the old build does not know
-- it exists, and the new build's writes are invisible to it. This is an EXPAND
-- with no contract half.
--
-- Reverse:
--   DROP TABLE IF EXISTS pledges;
--   Safe only before the platform has taken a pledge. Afterwards this table is
--   the record of what a backer committed to and what they were promised, and
--   `transactions` and `ledger_entries` (§7.2, not built yet) will reference it
--   as the thing money was moved for. As everywhere else in this directory, the
--   way back from a bad release is the previous build of the application; this
--   block is for a database that has served no traffic.
--
--   Dropping it does NOT undo reservation's effect on `reward_tiers`: a draft
--   that had incremented `reserved_quantity` leaves that count behind with
--   nothing left to explain it. Anyone genuinely reversing this on a database
--   with rows in it therefore also runs
--   `UPDATE reward_tiers SET reserved_quantity = 0;` — which is correct exactly
--   because the drafts being dropped were the only writers of it.

-- ---------------------------------------------------------------------------
-- pledges
-- ---------------------------------------------------------------------------

CREATE TABLE pledges (
    id                     uuid           PRIMARY KEY,
    -- Cascades, for the reason V7 gives about items: a pledge has no meaning
    -- without the campaign it backs, and a campaign that can still be
    -- hard-deleted is one that never launched — which is to say one that has no
    -- pledges. When `transactions` lands it will refuse the deletion from the
    -- other end, because by then there is money to account for.
    project_id             uuid           NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    -- Does not cascade, unlike the campaign. §17.4 anonymises an account rather
    -- than deleting it, precisely so that what it did survives it: a pledge is a
    -- financial obligation, and deleting the person does not undo the charge.
    backer_id              uuid           NOT NULL REFERENCES users (id),
    -- Null is a pledge without a reward — §4.5's PL-02, support only. It is a
    -- real and common shape, which is why the reference below is not NOT NULL
    -- and why every query that reads it has to allow for it.
    reward_tier_id         uuid,
    state                  text           NOT NULL DEFAULT 'DRAFT',

    -- Money, five ways, all numeric(14,2) and BigDecimal above. §4.5's PL-06:
    -- reward + add-ons + shipping + tax, with bonus support on top.
    --
    -- Separate columns rather than one total, because a backer is shown the
    -- breakdown and a creator is paid on part of it: the platform fee (§5.2) is
    -- charged on what was pledged and not on the tax collected with it, and a
    -- single number cannot be split back apart afterwards.
    --
    -- Default zero rather than null. A draft that has selected a reward but not
    -- yet a destination has no shipping charge *yet*, and null would propagate
    -- through the generated total below and make it null too — a pledge whose
    -- total is unknown is one nothing can display, sort, or sum.
    base_amount            numeric(14, 2) NOT NULL DEFAULT 0,
    addons_amount          numeric(14, 2) NOT NULL DEFAULT 0,
    bonus_amount           numeric(14, 2) NOT NULL DEFAULT 0,
    shipping_amount        numeric(14, 2) NOT NULL DEFAULT 0,
    tax_amount             numeric(14, 2) NOT NULL DEFAULT 0,
    -- §7.2 says generated, and generated is what stops the total disagreeing
    -- with its parts. A column the application maintains is one some future code
    -- path updates a component of and forgets, and the symptom is a backer
    -- charged an amount that no line on their receipt explains. STORED rather
    -- than VIRTUAL because it is read far more often than written and because
    -- PostgreSQL 16 has no virtual generated columns anyway.
    total_amount           numeric(14, 2) GENERATED ALWAYS AS
                               (base_amount + addons_amount + bonus_amount + shipping_amount + tax_amount) STORED,
    -- Denormalised from the campaign, as on `reward_tiers` and for the same
    -- reason: an amount without a currency is not money, and a receipt is priced
    -- from this row rather than from a join two tables away.
    currency               text           NOT NULL DEFAULT 'AZN',
    -- **No foreign key, deliberately.** `payment_methods` is #55, which is
    -- blocked on the payment provider abstraction (#60), so there is no table to
    -- reference yet. Carried as a nullable uuid now rather than added later
    -- because it is null for the whole of a pledge's life until confirmation
    -- anyway (§4.5: the card is entered after the draft exists), so the column
    -- is honest in the meantime and #55 adds the reference under
    -- expand-then-contract without touching any writer.
    payment_method_id      uuid,
    -- ISO 3166-1 alpha-2, like `shipping_rules.country_code`, and null until the
    -- backer says where it goes. Which rate applies is looked up from this.
    shipping_country       text,
    -- §4.5's PL-12. Hides the backer from the public list; it does not hide them
    -- from the creator, who has to ship the reward to somebody.
    is_anonymous           boolean        NOT NULL DEFAULT false,
    -- §4.5's PL-16: taken after the deadline, while the campaign is in
    -- LATE_PLEDGE. Recorded on the pledge because it changes what the pledge
    -- manager may do with it and because a creator reporting on their campaign
    -- needs the two totals apart.
    is_late_pledge         boolean        NOT NULL DEFAULT false,
    -- Which referral link brought this backer, for §4.7's referrers report. Free
    -- text from a query parameter, so it is bounded below and nothing is
    -- resolved from it here.
    referrer_code          text,
    -- §10.3 requires `Idempotency-Key` on every payment mutation, and the unique
    -- index below is what makes a retried request return the original pledge
    -- rather than create a second one. Nullable because the header handling is
    -- #52: until then a draft is created without one, and a partial unique index
    -- is an index of the rows that have one.
    idempotency_key        text,

    -- **The reservation.** When this draft stops holding its place.
    --
    -- §4.5's sequence diagram: "Reserve stock (5 min TTL)". The value is
    -- computed by the application from the injected Clock and a configured TTL,
    -- not by a DEFAULT here — a default would make the database a second source
    -- of a rule that already exists in one place, and it would be unreachable
    -- from a test that needs to ask what happens after five minutes without
    -- waiting five minutes.
    reservation_expires_at timestamptz,

    confirmed_at           timestamptz,
    collected_at           timestamptz,
    -- Also when a draft's reservation lapsed. §7.2 gives three timestamps and
    -- this is the one that means "the pledge stopped being active": EXPIRED
    -- reaches that by timeout rather than by anybody deciding, which is a
    -- difference the `state` column already records. A fourth timestamp would be
    -- written by one job and read by one query.
    canceled_at            timestamptz,

    -- Optimistic locking (#51's "with optimistic locking"), mapped as @Version.
    --
    -- It guards the pledge against two writers, which is an ordinary situation
    -- here: a backer editing their pledge in one tab while the other confirms
    -- it, and the sweep expiring a draft while its owner is confirming it. The
    -- loser of that race has something useful to do — re-read and retry, or tell
    -- the backer their reservation lapsed — which is what makes an optimistic
    -- lock the right one and a row lock held across a checkout the wrong one.
    --
    -- It is NOT what makes the stock count correct. That is the conditional
    -- UPDATE against `reward_tiers` plus V7's check constraint: a version on
    -- this row cannot say anything about a place on another one.
    version                bigint         NOT NULL DEFAULT 0,
    created_at             timestamptz    NOT NULL DEFAULT now(),
    updated_at             timestamptz    NOT NULL DEFAULT now(),

    -- §6.2's vocabulary, exactly. A state outside it is a pledge no code in the
    -- platform knows how to move, collect, or refund, and the only place that
    -- can be refused for every writer — the application, a migration, a support
    -- query — is here.
    CONSTRAINT pledges_state_known CHECK (
        state IN (
            'DRAFT',                -- a reservation: stock is held, nothing is charged
            'CONFIRMED',            -- card verified, waiting for the campaign to close
            'EXPIRED',              -- the reservation TTL elapsed; the place went back
            'CANCELED_BY_BACKER',
            'CANCELED_BY_PROJECT',
            'CHARGE_PENDING',       -- the campaign succeeded and collection is queued
            'COLLECTED',
            'CHARGE_FAILED',
            'DROPPED',              -- the retry window elapsed without a successful charge
            'REFUNDED',
            'CHARGEBACK',
            'FULFILLED'
        )
    ),

    -- Money is never negative on any line. A discount expressed as a negative
    -- shipping charge is a total a backer cannot reconcile with their receipt,
    -- and a negative bonus is a backer paying less than the reward's price for
    -- it. §5.3's real floor — the smallest amount a card can be charged — is a
    -- rule about what may be CONFIRMED rather than about what may be drafted,
    -- and it belongs with the confirmation path (#52).
    CONSTRAINT pledges_base_amount_is_not_negative CHECK (base_amount >= 0),
    CONSTRAINT pledges_addons_amount_is_not_negative CHECK (addons_amount >= 0),
    CONSTRAINT pledges_bonus_amount_is_not_negative CHECK (bonus_amount >= 0),
    CONSTRAINT pledges_shipping_amount_is_not_negative CHECK (shipping_amount >= 0),
    CONSTRAINT pledges_tax_amount_is_not_negative CHECK (tax_amount >= 0),
    CONSTRAINT pledges_currency_shape CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT pledges_shipping_country_shape CHECK (
        shipping_country IS NULL OR shipping_country ~ '^[A-Z]{2}$'
    ),
    CONSTRAINT pledges_referrer_code_length CHECK (
        referrer_code IS NULL OR length(btrim(referrer_code)) BETWEEN 1 AND 64
    ),
    CONSTRAINT pledges_idempotency_key_length CHECK (
        idempotency_key IS NULL OR length(btrim(idempotency_key)) BETWEEN 8 AND 255
    ),

    -- **A draft always knows when it lapses.** Without this a draft could be
    -- written with a null expiry, and the sweep's `WHERE reservation_expires_at
    -- <= now()` would never match it: the place it holds would be held for ever,
    -- silently, on the one kind of tier where that matters. This is the
    -- constraint that makes "time-bounded" a property of the data rather than of
    -- whichever code path remembered.
    --
    -- The converse is deliberately not required. A pledge that has moved on
    -- keeps the expiry it had, because it is a true statement about the window
    -- the backer actually had, and requiring it to be cleared would be one more
    -- thing every transition has to remember for no reader's benefit.
    CONSTRAINT pledges_drafts_are_time_bounded CHECK (
        state <> 'DRAFT' OR reservation_expires_at IS NOT NULL
    ),

    -- The composite reference of V7's `reward_tier_items`, for the same reason:
    -- without `project_id` in the key, a pledge on one campaign could name a
    -- reward tier from another, and no single-column reference can refuse that.
    -- `reward_tiers_identity_within_project` is the unique key it points at.
    -- Unmatched when `reward_tier_id` is null, which is what lets a pledge
    -- without a reward exist at all.
    --
    -- NO ACTION, so a tier somebody is mid-checkout on cannot be deleted out
    -- from under them — RewardService already refuses to delete a tier with
    -- backers, and this is what holds when the caller is a support query.
    -- DEFERRABLE INITIALLY DEFERRED for exactly V7's reason: deleting a campaign
    -- cascades to `pledges` and to `reward_tiers` from one statement, and a check
    -- performed the moment the tier row goes would refuse it on the strength of
    -- a pledge the same statement is about to remove.
    CONSTRAINT pledges_reference_a_tier_of_the_same_project
        FOREIGN KEY (reward_tier_id, project_id) REFERENCES reward_tiers (id, project_id)
        ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED
);

-- §7.2: "one pledge per backer per project — where the pledge is active".
--
-- **Which states are active is a decision, and this is it.** Active means the
-- pledge still represents a commitment between this backer and this campaign:
-- the six states from a held place through to a delivered reward. The six left
-- out are the ones where the commitment has ended — the reservation lapsed,
-- somebody canceled, the collection window elapsed, or the money went back —
-- and after any of those the backer must be able to back the campaign again.
-- Blocking them would mean an abandoned checkout costs somebody the campaign for
-- ever.
--
-- DRAFT is in the active set, and that is the half that matters for #51. A draft
-- holds a place on a limited tier, so if a backer could hold two, one person
-- reloading the checkout page could exhaust an early-bird tier by themselves —
-- and every one of those places would sit unavailable until the sweep released
-- it. The cost is that a backer with a live draft cannot start a second one,
-- which is the correct behaviour and is why ReservationService expires the
-- caller's own lapsed draft before refusing them.
CREATE UNIQUE INDEX pledges_project_backer_active_key ON pledges (project_id, backer_id)
    WHERE state IN (
        'DRAFT', 'CONFIRMED', 'CHARGE_PENDING', 'CHARGE_FAILED', 'COLLECTED', 'FULFILLED'
    );

-- §8.4's `reservation-cleaner`, every minute: "which drafts have lapsed". Partial
-- over DRAFT because that is the only state the sweep looks at, which keeps the
-- index the size of the checkouts currently in flight rather than the size of the
-- table — on a platform where every pledge starts as a draft, the difference is
-- the whole history against a handful of rows.
CREATE INDEX pledges_expiring_drafts_idx ON pledges (reservation_expires_at) WHERE state = 'DRAFT';

-- §10.3's idempotency. Unique because that is the entire guarantee: a retried
-- POST /v1/pledges/draft must find the pledge it already made. Partial because
-- the key is null until #52 handles the header, and a unique index over nulls is
-- an index of nothing.
CREATE UNIQUE INDEX pledges_idempotency_key_key ON pledges (idempotency_key) WHERE idempotency_key IS NOT NULL;

-- "Which pledges hold this tier" — asked by the sweep's release when it needs
-- the tier a lapsed draft was holding, and asked by PostgreSQL itself on every
-- attempt to delete a tier, which without an index is a sequential scan of every
-- pledge ever taken.
CREATE INDEX pledges_reward_tier_idx ON pledges (reward_tier_id, project_id) WHERE reward_tier_id IS NOT NULL;

-- §4.8's "my pledges". Not partial: a backer's list includes the ones that
-- ended, which is exactly the set the active index above excludes.
CREATE INDEX pledges_backer_idx ON pledges (backer_id);

CREATE TRIGGER pledges_set_updated_at
    BEFORE UPDATE ON pledges
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON TABLE pledges IS
    'What a backer commits to. A DRAFT row is §4.5''s stock reservation, time-bounded by reservation_expires_at.';
COMMENT ON COLUMN pledges.reservation_expires_at IS
    'When this draft stops holding its place. Released by §8.4''s reservation-cleaner. See V17 for why this is PostgreSQL and not a Redis TTL.';
COMMENT ON COLUMN pledges.payment_method_id IS
    'No foreign key yet: payment_methods is #55, blocked on #60. Added under expand-then-contract when that table exists.';
COMMENT ON COLUMN pledges.total_amount IS
    'Generated. The parts are the source of truth so the total cannot disagree with the receipt.';
COMMENT ON COLUMN pledges.version IS
    'Optimistic locking, mapped as @Version. What keeps the stock count correct is the conditional update on reward_tiers, not this.';
