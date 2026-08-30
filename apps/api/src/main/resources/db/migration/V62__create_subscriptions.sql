-- Publishing becomes something an account holds an entitlement to: a plan
-- catalogue the console owns, and one subscription per account against it.
--
-- ---------------------------------------------------------------------------
-- Reverse:
--   DROP TABLE IF EXISTS subscriptions;
--   DROP TABLE IF EXISTS subscription_plans;
--
--   In that order -- subscriptions references plans.
--
--   Lossy, and in a direction worth naming. Every subscription row records what
--   somebody was charged and who confirmed the payment, and until #60 lands
--   there is no provider-side record to reconstruct it from: the money arrived
--   as a bank transfer and this table is the platform's only note of it. Export
--   both tables before reversing.
--
--   Reversing is otherwise safe for the platform's behaviour. The gate reads
--   through `PublishingEntitlement`, so a deployment without these tables has a
--   bean that answers "no subscription" to everything -- which closes campaign
--   submission for every creator rather than opening it for all of them.
--   Fail-closed, and at three in the morning that is the wrong kind of quiet.
-- ---------------------------------------------------------------------------
--
-- ---------------------------------------------------------------------------
-- WHY A TABLE AND NOT CONFIGURATION, AND WHY IT IS EDITABLE WHEN V49 IS NOT
-- ---------------------------------------------------------------------------
--
-- The first half is V49's argument and needs no repeating: a price is a number
-- an operator changes without a deployment, and the console is where they
-- change it.
--
-- The second half is a deliberate departure from V49 and does need stating,
-- because the two tables look alike and behave oppositely. A fee schedule may
-- not be edited: a payout collected in March was computed against March's rate,
-- and editing the row would silently rewrite what that payout should have been.
-- §22.1 asks that question with a seven-year retention rule attached.
--
-- A plan carries no such history, because what a subscriber was charged is
-- written onto their own row (`subscriptions.price`) at the moment they bought
-- it. Editing a plan cannot reach backwards into anybody's bill. So a plan is
-- an ordinary editable row, and the close-then-open dance V49 needs would here
-- be ceremony protecting nothing.
--
-- What editing a plan *does* change is the limits of everybody currently on it,
-- because those are read live rather than snapshotted. That asymmetry is the
-- point and it is argued at `subscriptions.price` below.
-- ---------------------------------------------------------------------------

CREATE TABLE subscription_plans (
    id uuid PRIMARY KEY,

    -- What an operator, a log line and a support conversation agree on. Upper
    -- case and unique, so that "the Growth plan" resolves to one row however the
    -- display name has been reworded since.
    --
    -- Free text rather than a CHECK against a closed list, unlike almost every
    -- other coded column on this platform. The list is data: an operator adds a
    -- plan from the console, and a constraint here would make that a migration.
    -- The shape is constrained instead, which is what keeps `starter`,
    -- `Starter ` and `STARTER` from being three plans.
    code text NOT NULL
        CONSTRAINT subscription_plans_code_shape CHECK (code ~ '^[A-Z][A-Z0-9_]{1,39}$'),

    name text NOT NULL
        CONSTRAINT subscription_plans_name_present CHECK (length(btrim(name)) BETWEEN 1 AND 120),

    -- What the pricing page says under the name. Optional: a plan whose name is
    -- self-explanatory should not be padded out to satisfy a NOT NULL.
    description text
        CONSTRAINT subscription_plans_description_length CHECK (
            description IS NULL OR length(description) <= 2000),

    -- numeric, never double precision. CLAUDE.md, and §7.2's shape for every
    -- money column on this platform: numeric(14,2), which is what
    -- `MoneyAmountConverter` refuses anything finer than.
    --
    -- Zero is allowed and means a free tier. That is not a loophole in the gate:
    -- the gate asks for a subscription, and a free plan is a subscription an
    -- operator has decided costs nothing. Whether the catalogue has one is the
    -- operator's decision to take from the console, per deployment.
    price numeric(14, 2) NOT NULL
        CONSTRAINT subscription_plans_price_sane CHECK (price >= 0),

    currency text NOT NULL DEFAULT 'AZN'
        CONSTRAINT subscription_plans_currency_shape CHECK (currency ~ '^[A-Z]{3}$'),

    billing_period text NOT NULL
        CONSTRAINT subscription_plans_billing_period_known CHECK (billing_period IN ('MONTHLY', 'YEARLY')),

    -- ---------------------------------------------------------------------
    -- THE LIMITS
    -- ---------------------------------------------------------------------
    --
    -- Null means "no limit", consistently, in both of them. The alternative --
    -- a sentinel like 0 or 2147483647 -- puts the unlimited case into the same
    -- arithmetic as the limited one, and the comparison that forgets is a
    -- creator refused for holding more than zero campaigns.

    -- How many campaigns this account may have in the platform's hands at once:
    -- submitted, in review, approved, scheduled, live, collecting. Drafts do not
    -- count, because a draft is private and costs the platform nothing.
    max_active_campaigns integer
        CONSTRAINT subscription_plans_max_active_sane CHECK (
            max_active_campaigns IS NULL OR max_active_campaigns >= 1),

    -- The largest funding goal a campaign on this plan may be submitted with.
    --
    -- Interacts with §5.3's configured `goalMaximum`, which is the platform's
    -- own ceiling, and the two are not the same rule: the platform's bound is
    -- the largest campaign it is willing to underwrite at all, and this is the
    -- largest one this plan is sold as covering. The lower of the two wins,
    -- which is what `SubmissionChecklist` and the entitlement check do
    -- independently rather than by comparing the numbers.
    goal_ceiling numeric(14, 2)
        CONSTRAINT subscription_plans_goal_ceiling_sane CHECK (
            goal_ceiling IS NULL OR goal_ceiling > 0),

    -- Whether the pricing page offers it.
    --
    -- This is how a plan leaves the catalogue, and there is deliberately no
    -- delete. Deleting a plan would either orphan its subscribers or cascade
    -- them away, and a creator whose subscription vanished because somebody
    -- tidied the price list is the worse of the two. Unlisting stops it being
    -- sold and leaves everybody on it exactly where they were.
    listed boolean NOT NULL DEFAULT true,

    -- The order the pricing page draws them in, cheapest-first being a
    -- convention rather than a rule: an operator who wants Growth in the middle
    -- with a badge on it should not have to reprice anything to get it there.
    sort_order integer NOT NULL DEFAULT 0,

    created_at timestamptz NOT NULL DEFAULT now(),

    updated_at timestamptz NOT NULL DEFAULT now(),

    -- Null means "shipped with the platform", which is what the three seeded
    -- rows below are. Nullable for exactly that reason: a migration has no user
    -- to attribute a row to, and inventing a system account to satisfy a NOT
    -- NULL would put a row in `users` that nobody can sign in as and that every
    -- account query then has to know to exclude.
    --
    -- Anything the console writes carries a real account. That is enforced in
    -- `SubscriptionPlans`, which is the only thing that writes here.
    created_by uuid REFERENCES users (id) ON DELETE SET NULL,

    CONSTRAINT subscription_plans_code_key UNIQUE (code)
);

-- The pricing page's own query: the listed plans, in the order they are drawn.
-- Partial, because the unlisted ones are read only by the console, which reads
-- the whole table and does not need an index to do it.
CREATE INDEX subscription_plans_listed_order
    ON subscription_plans (sort_order, price)
    WHERE listed;

COMMENT ON TABLE subscription_plans IS
    'What the platform charges a creator to publish, and what each plan allows.';

-- ---------------------------------------------------------------------------
-- WHAT AN ACCOUNT HOLDS
-- ---------------------------------------------------------------------------

CREATE TABLE subscriptions (
    id uuid PRIMARY KEY,

    -- ON DELETE CASCADE, and not by preference. The test suites truncate
    -- `users`; a foreign key to that table with NO ACTION breaks every suite
    -- that does, several modules away from anything about subscriptions. It is
    -- also right on the merits: a closed account's subscription is not a record
    -- anybody consults, unlike the ledger entries beside it.
    account_id uuid NOT NULL
        REFERENCES users (id) ON DELETE CASCADE,

    -- ON DELETE RESTRICT because a plan is never deleted -- see `listed` above.
    -- This is the constraint that makes that sentence true rather than merely
    -- intended.
    plan_id uuid NOT NULL
        REFERENCES subscription_plans (id) ON DELETE RESTRICT,

    -- Text with a CHECK rather than a PostgreSQL enum type, for V19's reason:
    -- adding a value to an enum type cannot run in the same transaction as the
    -- statements using it, which makes a rolling deployment awkward for no gain.
    --
    -- PENDING_PAYMENT is the state a paid plan is bought into, because nothing
    -- on this platform can charge a card: §9.2 ships no provider adapter while
    -- #60 is unanswered. A member of staff records that payment arrived and the
    -- row becomes ACTIVE. That is how a platform with no processor sells --
    -- an invoice and a transfer -- rather than a stub pretending to be one.
    --
    -- A plan priced at zero skips it: there is no payment to wait for.
    state text NOT NULL
        CONSTRAINT subscriptions_state_known CHECK (
            state IN ('PENDING_PAYMENT', 'ACTIVE', 'CANCELED', 'EXPIRED')),

    -- ---------------------------------------------------------------------
    -- WHY THE PRICE IS COPIED HERE AND THE LIMITS ARE NOT
    -- ---------------------------------------------------------------------
    --
    -- These three are snapshotted from the plan at the moment of purchase. The
    -- limits are not: they are read live from `subscription_plans` on every
    -- check.
    --
    -- The halves are treated differently because their failure modes are not
    -- comparable. A price that moved under a subscriber is a bill they never
    -- agreed to, and there is no defensible reading of "we changed the plan" in
    -- which last month's charge becomes a different number.
    --
    -- A limit that moved under a subscriber is either a gift -- the operator
    -- raised what Starter allows, and everybody on Starter gets it, which is
    -- what raising a limit means -- or a reduction, which reaches nobody
    -- retroactively because the gate only refuses *new* submissions. A creator
    -- already holding three campaigns is not asked to withdraw one.
    --
    -- Snapshotting the limits as well would mean an operator raising a limit had
    -- raised it for nobody who had already bought, which is the opposite of what
    -- they meant, and would leave the platform holding as many limit sets as it
    -- has ever had subscribers.
    price numeric(14, 2) NOT NULL
        CONSTRAINT subscriptions_price_sane CHECK (price >= 0),

    currency text NOT NULL
        CONSTRAINT subscriptions_currency_shape CHECK (currency ~ '^[A-Z]{3}$'),

    billing_period text NOT NULL
        CONSTRAINT subscriptions_billing_period_known CHECK (billing_period IN ('MONTHLY', 'YEARLY')),

    -- When it became ACTIVE. Null while it is waiting for payment, and that is
    -- the distinction the column carries: `created_at` says when somebody chose
    -- a plan, this says when the platform started owing them anything.
    started_at timestamptz,

    -- When the entitlement stops. Null while pending, for the same reason.
    current_period_end timestamptz,

    CONSTRAINT subscriptions_period_follows_start CHECK (
        current_period_end IS NULL OR started_at IS NULL OR current_period_end > started_at),

    -- An active subscription has both; a pending one has neither. Stated as a
    -- constraint rather than left to the service, because a row with a start and
    -- no end is one the entitlement query treats as never expiring.
    CONSTRAINT subscriptions_active_has_a_window CHECK (
        state <> 'ACTIVE' OR (started_at IS NOT NULL AND current_period_end IS NOT NULL)),

    -- A cancellation the creator has already paid for. The entitlement runs to
    -- `current_period_end` and is not renewed.
    --
    -- Separate from the CANCELED state, which is immediate and is what staff
    -- ending a subscription does. A creator who cancels has bought the month;
    -- taking it away the moment they click would be charging for something and
    -- then withdrawing it.
    cancel_at_period_end boolean NOT NULL DEFAULT false,

    canceled_at timestamptz,

    -- Who recorded that payment arrived. Null for a free plan, which nobody
    -- confirmed, and for anything #60's provider flow activates later.
    --
    -- ON DELETE SET NULL rather than RESTRICT, unlike `staff_role_grants`. That
    -- table restricts because a grant with no visible grantor is a live
    -- privilege nobody is answerable for. This is a historical note beside an
    -- `audit_logs` row that carries the same fact and does not cascade, so
    -- blocking the closure of a former colleague's account over it would be an
    -- obstacle protecting a duplicate.
    activated_by uuid REFERENCES users (id) ON DELETE SET NULL,

    -- Why, in the words of whoever activated or cancelled it: the invoice
    -- number, the transfer reference, the reason for ending it early.
    note text
        CONSTRAINT subscriptions_note_length CHECK (note IS NULL OR length(note) <= 2000),

    created_at timestamptz NOT NULL DEFAULT now(),

    updated_at timestamptz NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------
-- ONE OPEN SUBSCRIPTION PER ACCOUNT
-- ---------------------------------------------------------------------------
--
-- A partial unique index rather than a check in the service, because two
-- purchases arriving together both read "no subscription" and both insert. The
-- index makes one of them fail, and `Subscriptions.subscribe` turns the
-- constraint violation into a refusal the pricing page can retry.
--
-- It cannot consult a clock, which matters: an ACTIVE row whose period ended
-- last week is still ACTIVE to this index and would block the same account from
-- subscribing again. That is why `subscribe` closes an expired row to EXPIRED
-- inside its own transaction before inserting -- the stale row is retired by the
-- person it was in the way of, at the moment it was in the way.
--
-- There is no sweep job doing that on a schedule, deliberately. Nothing reads
-- `state` without also reading `current_period_end`: the entitlement query, the
-- console list and the creator's own view all derive what to show from the pair.
-- A job would exist only to make a column agree with a clock its readers are
-- already consulting.
CREATE UNIQUE INDEX subscriptions_one_open_per_account
    ON subscriptions (account_id)
    WHERE state IN ('PENDING_PAYMENT', 'ACTIVE');

-- The entitlement query, asked on every campaign submission: "what does this
-- account hold, and is it still running". Leads on the account because that is
-- what is always known.
CREATE INDEX subscriptions_by_account
    ON subscriptions (account_id, state, current_period_end DESC);

-- The console's queue: what is waiting for somebody to record a payment against
-- it, oldest first, because the person who has been waiting longest is the one
-- to serve. Partial, because that queue is short and the table is not.
CREATE INDEX subscriptions_awaiting_payment
    ON subscriptions (created_at)
    WHERE state = 'PENDING_PAYMENT';

COMMENT ON TABLE subscriptions IS
    'What an account has bought the right to publish under, and until when.';

-- ---------------------------------------------------------------------------
-- THE CATALOGUE THE PLATFORM SHIPS WITH
-- ---------------------------------------------------------------------------
--
-- Three rows, because the gate goes live with this migration and a gate with an
-- empty catalogue behind it is a platform nobody can publish on. They are
-- ordinary rows: an operator reprices, renames or unlists them from the console
-- on the first day, and nothing in the code names one of them.
--
-- `created_by` is null, which is what null means here: shipped, not chosen.
--
-- The prices are in AZN and are a starting position rather than a decision. The
-- shape of the ladder is the part that is deliberate: one campaign at a time at
-- the bottom, and no ceiling at the top, so that the limit a creator meets is
-- the one they can see the price of.
INSERT INTO subscription_plans
    (id, code, name, description, price, currency, billing_period,
     max_active_campaigns, goal_ceiling, listed, sort_order)
VALUES
    ('7c0d4f8e-6a1b-4c2d-9e3f-1a2b3c4d5e01',
     'STARTER',
     'Starter',
     'One campaign at a time, up to a 10,000 AZN goal. For a first project.',
     19.00, 'AZN', 'MONTHLY',
     1, 10000.00, true, 10),

    ('7c0d4f8e-6a1b-4c2d-9e3f-1a2b3c4d5e02',
     'GROWTH',
     'Growth',
     'Three campaigns at a time, up to a 100,000 AZN goal.',
     49.00, 'AZN', 'MONTHLY',
     3, 100000.00, true, 20),

    ('7c0d4f8e-6a1b-4c2d-9e3f-1a2b3c4d5e03',
     'PRO',
     'Pro',
     'As many campaigns as you can run, with no goal ceiling.',
     149.00, 'AZN', 'MONTHLY',
     NULL, NULL, true, 30);
