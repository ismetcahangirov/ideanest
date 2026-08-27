-- §21.2's "Rate source: central bank rates, cached hourly" — issue #327.
--
-- ---------------------------------------------------------------------------
-- Reverse:
--   DROP TABLE IF EXISTS exchange_rates;
--
--   Cheap, and it stays cheap. Nothing references these rows and nothing is
--   decided by them: a rate is fetched from a public source and can be fetched
--   again, and the one place a rate has to survive is `pledges.display_rate`,
--   which V60 stores as a value rather than as a reference. Dropping this table
--   costs one refresh cycle and loses no audit.
-- ---------------------------------------------------------------------------
--
-- ---------------------------------------------------------------------------
-- WHAT `rate` MEANS, EXACTLY
-- ---------------------------------------------------------------------------
--
-- **One unit of `quote_currency` is worth `rate` units of `base_currency`.**
--
-- That sentence is the whole schema, and it is spelled out because "base" and
-- "quote" are the pair of words the FX world is least consistent about. With
-- base `AZN` and quote `USD`, a rate of 1.7000000000 means one dollar costs
-- 1.70 manat -- so an amount in manat is divided by the rate to approximate it
-- in dollars, and multiplied to go the other way.
--
-- It is also exactly the shape the Central Bank of Azerbaijan publishes: its
-- document is titled "AZN məzənnələri" and each `<Valute>` carries a `<Nominal>`
-- and a `<Value>` in manat. Some nominals are 100 -- the rouble is quoted per
-- hundred -- so the client divides `Value` by `Nominal` before this row is
-- written, and every row here is per one unit. Storing the nominal instead
-- would push that division into every reader.
--
-- ---------------------------------------------------------------------------
-- WHY `published_for` IS A DATE AND NOT THE FETCH TIME
-- ---------------------------------------------------------------------------
--
-- §21.2 asks for the cache to be hourly and the source publishes daily. Those
-- are not in conflict: the hourly refresh is how quickly the platform notices a
-- new publication, and `published_for` is the day the central bank says the
-- rate is in force from. The two are recorded separately because they answer
-- different questions -- "how old is the number we showed" is the first, and
-- "which official rate was it" is the second.
--
-- The source itself makes the distinction unavoidable. Asking cbar.az for a
-- Sunday returns a document whose own `Date` attribute is the preceding Friday:
-- it serves the last published day rather than refusing. So the date in the
-- document is believed and the date in the request is not, and the unique index
-- below is what makes a weekend of identical answers one row rather than three.
--
-- ---------------------------------------------------------------------------
-- WHY THE ROWS ARE KEPT RATHER THAN OVERWRITTEN
-- ---------------------------------------------------------------------------
--
-- An UPSERT onto (source, base, quote) would keep the table at forty rows and
-- lose the answer to "what was the official rate on the day this pledge was
-- made". `pledges.display_rate` records the figure a backer was actually shown,
-- which is the audit §21.2 asks for -- but that is the platform's word for it,
-- and a history here is the independent record it can be checked against.
--
-- Forty rows a day is fourteen thousand a year. There is no retention sweep and
-- deliberately no partitioning: this is the smallest table in the schema and
-- will still be in ten years.

CREATE TABLE exchange_rates (
    id uuid PRIMARY KEY,

    -- Where it came from. A text column rather than an enum type for the reason
    -- every other one in this schema is: adding a value to a PostgreSQL enum is
    -- a migration, and a second rate source is a configuration change.
    source text NOT NULL,

    -- The currency the rate is expressed IN. `AZN` for everything the platform
    -- publishes today, because §21.2 phase 1 collects in manat and a display
    -- currency is an approximation of an amount denominated in it.
    base_currency text NOT NULL,

    -- The currency being priced. One unit of this is worth `rate` of the base.
    quote_currency text NOT NULL,

    -- numeric(20,10) rather than numeric(14,2). This is NOT money: it is a
    -- ratio, and rounding it to two places would put a per-cent error into
    -- every converted amount. Ten places is more than any central bank
    -- publishes and costs nothing at this row count.
    rate numeric(20,10) NOT NULL,

    -- The day the source says this rate is in force from. See the note above on
    -- why this is not the fetch time.
    published_for date NOT NULL,

    -- When the platform last saw it. Answers "is the cache fresh", which is a
    -- different question from "which rate is this" -- a source that has been
    -- unreachable for a day has rows whose `published_for` is yesterday and
    -- whose `fetched_at` is yesterday too, and the display has to be able to
    -- tell that from a rate that simply has not changed.
    fetched_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT exchange_rates_base_shape CHECK (base_currency ~ '^[A-Z]{3}$'),
    CONSTRAINT exchange_rates_quote_shape CHECK (quote_currency ~ '^[A-Z]{3}$'),
    -- A currency priced in itself is 1 by definition, and a row saying so is a
    -- row that can be wrong. The conversion code answers that case without
    -- asking the database.
    CONSTRAINT exchange_rates_pair_differs CHECK (base_currency <> quote_currency),
    -- Zero is not a rate; it is a division by zero waiting for a reader.
    CONSTRAINT exchange_rates_positive CHECK (rate > 0),
    CONSTRAINT exchange_rates_source_length CHECK (length(btrim(source)) BETWEEN 1 AND 32),

    -- One row per rate per day per source. This is what makes the hourly
    -- refresh idempotent: eleven of the twelve daily passes see the row they
    -- already wrote and do nothing.
    CONSTRAINT exchange_rates_publication_key UNIQUE (source, base_currency, quote_currency, published_for)
);

-- The one read this table has: the newest rate for a pair. `published_for DESC`
-- so the answer is the first row of the scan rather than a sort over the year.
CREATE INDEX exchange_rates_newest_idx
    ON exchange_rates (source, base_currency, quote_currency, published_for DESC);

COMMENT ON TABLE exchange_rates IS
    '§21.2 (#327): central bank rates, refreshed hourly. One unit of quote_currency is worth `rate` units of base_currency.';

COMMENT ON COLUMN exchange_rates.rate IS
    'Units of base_currency per ONE unit of quote_currency. Normalised from the source''s nominal, so a rouble quoted per 100 is stored per 1.';

COMMENT ON COLUMN exchange_rates.published_for IS
    'The day the source says the rate is in force from, read from the document rather than from the request: cbar.az answers a Sunday with Friday''s publication.';
