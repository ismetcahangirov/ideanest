-- §21.2's "Rate retention: the rate used is stored on the pledge, for audit" —
-- issue #327.
--
-- ---------------------------------------------------------------------------
-- Reverse:
--   ALTER TABLE pledges
--       DROP CONSTRAINT IF EXISTS pledges_display_rate_is_whole,
--       DROP CONSTRAINT IF EXISTS pledges_display_currency_shape,
--       DROP CONSTRAINT IF EXISTS pledges_display_currency_differs,
--       DROP CONSTRAINT IF EXISTS pledges_display_rate_positive,
--       DROP COLUMN IF EXISTS display_rate,
--       DROP COLUMN IF EXISTS display_currency;
--
--   Safe under a rolling deployment in both directions: both columns are
--   nullable with no default, nothing joins on them, and no code path other
--   than confirmation writes them. What is lost is the record of what
--   approximation a backer was shown, which is a claim nobody can reconstruct
--   afterwards -- `exchange_rates` keeps the official rate for the day, and
--   that is evidence rather than a substitute.
-- ---------------------------------------------------------------------------
--
-- ---------------------------------------------------------------------------
-- WHY A PLEDGE RECORDS AN APPROXIMATION IT WAS NEVER CHARGED IN
-- ---------------------------------------------------------------------------
--
-- §21.2 is explicit that collection occurs in the project's currency and that
-- the display currency is an approximation. So this is not a second amount: it
-- is the answer to "what did we tell them this would cost", asked months later
-- by somebody holding a complaint that the figure moved.
--
-- Only the rate is stored, and never the converted amount. The amount is a
-- product of `total_amount` and this rate, and storing both would be storing a
-- number that can disagree with its own inputs -- which is the failure a
-- generated column exists to prevent and which no constraint here could catch.
--
-- ---------------------------------------------------------------------------
-- WHY BOTH COLUMNS ARE NULL FOR MOST PLEDGES, AND WHY THAT IS RIGHT
-- ---------------------------------------------------------------------------
--
-- A backer whose display currency is the campaign's currency was shown no
-- approximation at all: the figure on the checkout was the figure being
-- charged. Recording a rate of 1 for them would be recording a conversion that
-- did not happen, and `pledges_display_currency_differs` refuses it.
--
-- Both columns are therefore null together or set together, which
-- `pledges_display_rate_is_whole` enforces. A currency with no rate beside it
-- would be a claim that an approximation was shown without saying what it was.

ALTER TABLE pledges
    ADD COLUMN display_currency text,
    -- numeric(20,10), matching `exchange_rates.rate` exactly. See that migration
    -- for why a rate is not money and must not be rounded to two places.
    ADD COLUMN display_rate numeric(20,10);

ALTER TABLE pledges
    ADD CONSTRAINT pledges_display_currency_shape
        CHECK (display_currency IS NULL OR display_currency ~ '^[A-Z]{3}$'),
    ADD CONSTRAINT pledges_display_rate_positive
        CHECK (display_rate IS NULL OR display_rate > 0),
    -- Null together or set together. Neither half means anything alone.
    ADD CONSTRAINT pledges_display_rate_is_whole
        CHECK ((display_currency IS NULL) = (display_rate IS NULL)),
    -- No approximation was shown when the two currencies agree, so there is
    -- nothing to record. See the note above.
    ADD CONSTRAINT pledges_display_currency_differs
        CHECK (display_currency IS NULL OR display_currency <> currency);

COMMENT ON COLUMN pledges.display_currency IS
    '§21.2 (#327): the currency this pledge was approximated in at confirmation, or null when the backer was shown the campaign''s own.';

COMMENT ON COLUMN pledges.display_rate IS
    '§21.2 (#327): units of the pledge''s currency per ONE unit of display_currency, as of confirmation. The approximation shown, never the amount charged.';
