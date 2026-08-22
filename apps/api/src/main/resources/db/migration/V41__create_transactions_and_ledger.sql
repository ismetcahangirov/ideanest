-- §9.5's money flow, written down twice: once as what a provider did (#61's
-- `transactions`) and once as what it means (#62's `ledger_entries`).
--
-- ---------------------------------------------------------------------------
-- Reverse:
--   DROP TRIGGER IF EXISTS ledger_entries_balance ON ledger_entries;
--   DROP TRIGGER IF EXISTS ledger_entries_is_append_only ON ledger_entries;
--   DROP TRIGGER IF EXISTS transactions_is_append_only ON transactions;
--   DROP FUNCTION IF EXISTS ledger_entries_must_balance();
--   DROP FUNCTION IF EXISTS ledger_entries_refuse_change();
--   DROP FUNCTION IF EXISTS transactions_refuse_change();
--   DROP TABLE IF EXISTS ledger_entries;
--   DROP TABLE IF EXISTS transactions;
--
--   Cheap today and never again, and it is worth being exact about which of
--   those two it is. Every release before this one collected nothing, so both
--   tables are empty and dropping them loses nothing that ever happened. The
--   moment one charge succeeds that stops being true in a way no other table in
--   this schema shares: these rows are the only record that somebody's money
--   moved, they are what §19.4's recovery is measured against, and §22.1 makes
--   them a regulatory obligation rather than a convenience. **After the first
--   collection the reverse is a restore from backup, not a DROP.**
-- ---------------------------------------------------------------------------
--
-- ---------------------------------------------------------------------------
-- WHY TWO TABLES AND NOT ONE
-- ---------------------------------------------------------------------------
--
-- A charge is one call to a provider and four facts about the platform's books:
-- money arrived in escrow, some of it is the creator's, some is the platform's
-- fee, some is the provider's. One table would have to be one of the two, and
-- each choice loses the other.
--
--   * **Only `transactions`** — one row per provider call, with a `net_amount`
--     and a `fee` column — cannot express a split that grows. §9.5 already has
--     five destinations and #78's tax makes six; each one would be a column,
--     every report would be a different sum over a different subset of them, and
--     nothing in the database could say the columns add up.
--   * **Only `ledger_entries`** loses the provider's side entirely. A declined
--     charge moves no money, so it has no entries — and a declined charge is
--     precisely the row §9.6's retry schedule is driven from and the row a
--     support conversation about "my card was refused" is answered from.
--
-- So: `transactions` is **what we asked a provider to do and what it said**, and
-- it exists for attempts that moved nothing as much as for the ones that did.
-- `ledger_entries` is **what moved**, and it exists only when something did. The
-- link is `ledger_entries.transaction_id`, and the invariant below is stated per
-- transaction because a transaction is the unit that has to balance.
--
-- ---------------------------------------------------------------------------
-- BOTH ARE APPEND-ONLY, AND IN POSTGRESQL RATHER THAN BY CONVENTION
-- ---------------------------------------------------------------------------
--
-- §7.2 says `transactions` is "never updated or deleted. Corrections are new
-- rows", and the same is true of an entry: a ledger that can be edited is a
-- ledger, and one that cannot is an audit trail. V21 made that a statement-level
-- trigger for `audit_logs` and the argument transfers unchanged -- a rewrite rule
-- would succeed silently, and a revoked grant names a role the migration does not
-- know, does not bind the owner, and does not survive a restore.
--
-- **What this costs is that a status cannot be moved.** A charge row is written
-- once, carrying the outcome the provider gave: `SUCCEEDED`, `FAILED`, or
-- `PENDING` when the provider accepted the instruction and has not decided yet.
-- A `PENDING` that later resolves is a *new* row -- which is why
-- `provider_transaction_id` is not unique on its own; see the column. The
-- alternative, a mutable `status`, is the column every ledger fraud in the
-- textbooks is committed through.
--
-- ---------------------------------------------------------------------------
-- THE INVARIANT, AND WHY IT IS A DEFERRED CONSTRAINT TRIGGER
-- ---------------------------------------------------------------------------
--
-- §7.2: "for every `transaction_id`, SUM(debit) = SUM(credit). Enforced by a
-- database constraint and verified by a nightly reconciliation job."
--
-- It cannot be a `CHECK`, because a check sees one row and the invariant is about
-- a set of them. It cannot be an ordinary `AFTER INSERT` trigger either: the
-- first entry of a balanced pair is, on its own, unbalanced, so a trigger that
-- fired per statement would refuse every posting that inserts its entries one row
-- at a time. So it is a **`CONSTRAINT TRIGGER ... DEFERRABLE INITIALLY
-- DEFERRED`**, which PostgreSQL runs at commit: the transaction may pass through
-- any number of unbalanced intermediate states and may not *end* in one.
--
-- The consequence worth stating plainly is where the error surfaces. A posting
-- that does not balance fails at `COMMIT` and not at the `INSERT` that unbalanced
-- it, so the stack trace names the commit rather than the line. `Ledger` refuses
-- an unbalanced posting in Java first, before it writes anything, for exactly that
-- reason -- and this trigger is still what makes the rule true, because the Java
-- check protects the caller that goes through `Ledger` and this one protects the
-- table from a support script that does not.
--
-- ---------------------------------------------------------------------------
-- Rolling deployment
-- ---------------------------------------------------------------------------
--
-- Two new tables, three triggers, four indexes, and nothing else touched. No
-- column is dropped, no constraint is added to an existing table, and no previous
-- release reads or writes either table -- the older build does not know they
-- exist and collects nothing, which is #60's doing rather than this migration's.
-- Both halves of a rolling deploy are safe in either order. This is an EXPAND
-- with no contract half.

-- ---------------------------------------------------------------------------
-- transactions
-- ---------------------------------------------------------------------------

CREATE TABLE transactions (
    id                      uuid           PRIMARY KEY,

    -- **Which pledge this was about, when it was about one.**
    --
    -- Nullable, because §7.2's own type list includes `payout`, and a payout is
    -- about a campaign and a creator rather than about any single pledge. The
    -- check below pairs the two: everything except a payout names a pledge, and a
    -- payout does not.
    --
    -- `ON DELETE NO ACTION`, so the database refuses to remove a pledge that has
    -- been charged. That is the correct refusal and not an inconvenience: a
    -- collection with no pledge behind it is a payment nobody can explain, and
    -- §17.4 anonymises the person rather than deleting the row.
    pledge_id               uuid           REFERENCES pledges (id) ON DELETE NO ACTION,

    -- Which campaign's money this is. Denormalised from the pledge for the read
    -- this table exists for -- "everything that moved on this campaign", asked by
    -- the payout run, by reconciliation, and by support -- and mandatory even on a
    -- payout, which has no pledge to reach it through.
    project_id              uuid           NOT NULL REFERENCES projects (id) ON DELETE NO ACTION,

    -- §7.2's list, verbatim and upper-cased to match every other state column in
    -- this schema. `VERIFICATION` is #55's zero-value authorisation, `REFUND` is
    -- #67's, `CHARGEBACK` and `CHARGEBACK_REVERSAL` are #68's, `PAYOUT` is #69's;
    -- only `CHARGE` is written by anything today, and the others are here because
    -- adding a value to this check later is a migration over a table that will by
    -- then be the largest financial record the platform holds.
    type                    text           NOT NULL,

    -- What the provider said, frozen at insert. See the append-only note above:
    -- there is no path from `PENDING` to anything, because the row does not move.
    status                  text           NOT NULL,

    -- What was asked for, always positive. **Direction is not expressed here** --
    -- a refund is a `REFUND` row for a positive amount, not a `CHARGE` row for a
    -- negative one -- because the sign of a provider call is a property of what
    -- kind of call it was, and a signed amount here would be a second, silently
    -- disagreeing answer to the question `type` already answers.
    amount                  numeric(14, 2) NOT NULL,
    currency                text           NOT NULL,

    -- Which adapter made the call, from `ProviderName`. §9.3 requires at least two
    -- providers to be integrated, so "which one was this" is not answerable from
    -- configuration -- the answer has to be on the row, because the configuration
    -- will have changed by the time anybody asks.
    provider                text           NOT NULL,

    -- **The provider's own identifier for this call.** Null when there is not one:
    -- a request that timed out before the provider answered has no identifier, and
    -- that row still has to exist because it is the record of an attempt that may
    -- have charged somebody.
    --
    -- Deliberately **not unique on its own**, which is a departure from §7.2 and
    -- the append-only rule above is the reason. A `PENDING` charge that a webhook
    -- later resolves is a second row naming the same provider transaction, because
    -- the first row cannot be updated. What must not happen twice is a *successful*
    -- charge being recorded twice under one provider identifier, and
    -- `transactions_settled_provider_key` below is exactly that, no more.
    provider_transaction_id text,

    -- The provider's answer, verbatim, for the support conversation and for the
    -- dispute. `jsonb` and not `text` here -- unlike `outbox_events.payload` -- for
    -- the opposite reason to V19's: nothing consumes these bytes, everything
    -- *queries into* them ("which decline codes did we see on Tuesday"), and the
    -- document is the provider's rather than ours so there is no round-trip
    -- guarantee to preserve.
    --
    -- **§17.2's redaction applies before this column, not after it.** An adapter
    -- strips card data on the way in; a `jsonb` document is not a place to
    -- discover later that a PAN was stored.
    provider_response       jsonb,

    -- Why it failed, in the provider's vocabulary and then in words. Both null on
    -- anything that did not fail, and the check below holds them to it: a
    -- succeeded charge carrying a decline code is a row that will be read as a
    -- decline by the first query that filters on the column rather than on the
    -- status.
    failure_code            text,
    failure_message         text,

    -- **Which of §9.6's four attempts this was**, counted from one. On the
    -- transaction and not only on the pledge, because the pledge carries the
    -- current count and this table carries the history -- "the third attempt was
    -- refused for insufficient funds and the fourth for an expired card" is a
    -- sentence only these rows can produce.
    attempt_number          integer        NOT NULL DEFAULT 1,

    -- §9.3's R-08 and §17.2's "idempotency required on all payment mutations".
    -- **Unique across settled rows, and that uniqueness is the whole of the
    -- protection against double collection**: the key is derived from the pledge and
    -- the attempt number, so an attempt the platform has already settled cannot
    -- settle a second time, whatever the provider does or how many replicas try.
    --
    -- Unique across *settled* rows rather than all of them, for the same reason
    -- `provider_transaction_id` is: a `PENDING` charge and the row that resolves it
    -- share an attempt and therefore share a key, and neither can be updated into the
    -- other. What stops a second `PENDING` row accumulating on every re-poll is not
    -- an index -- it is that every charge on one pledge is serialised by a lock on
    -- the pledge row, so `CollectionRun` can check for an existing `PENDING` row and
    -- be right.
    --
    -- It is not `shared/idempotency`'s table and does not want to be, for the
    -- reason V19 gives about `outbox_events.id`: that one answers "what did we
    -- answer this account when it last sent this key", carries an `account_id`
    -- and an HTTP status, and is swept after §17.2's 24 hours. A collection has no
    -- account sending it, no HTTP response, and a retry window of seven days.
    idempotency_key         text           NOT NULL,

    created_at              timestamptz    NOT NULL DEFAULT now(),

    CONSTRAINT transactions_type_known CHECK (
        type IN ('VERIFICATION', 'CHARGE', 'REFUND', 'CHARGEBACK', 'CHARGEBACK_REVERSAL', 'PAYOUT')
    ),
    CONSTRAINT transactions_status_known CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED')),
    -- A payout is about a campaign; everything else is about a pledge. Stated as a
    -- constraint because the alternative is a nullable column that everything
    -- reads as optional, and a charge with no pledge is a charge nobody can
    -- attribute.
    CONSTRAINT transactions_pledge_matches_type CHECK (
        (type = 'PAYOUT') = (pledge_id IS NULL)
    ),
    -- Zero is not a payment. §9.3's R-05 zero-value verification is the one call
    -- that legitimately moves nothing, and it is a `VERIFICATION`.
    CONSTRAINT transactions_amount_is_not_negative CHECK (amount >= 0),
    CONSTRAINT transactions_amount_is_positive_unless_verification CHECK (
        type = 'VERIFICATION' OR amount > 0
    ),
    CONSTRAINT transactions_currency_is_iso CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT transactions_provider_length CHECK (length(btrim(provider)) BETWEEN 1 AND 32),
    CONSTRAINT transactions_provider_transaction_id_length CHECK (
        provider_transaction_id IS NULL OR length(btrim(provider_transaction_id)) BETWEEN 1 AND 255
    ),
    CONSTRAINT transactions_failure_belongs_to_a_failure CHECK (
        status = 'FAILED' OR (failure_code IS NULL AND failure_message IS NULL)
    ),
    -- The other direction. A failure nobody wrote a reason for is the row that
    -- turns "why was this card refused" into an afternoon with the provider's
    -- support desk.
    CONSTRAINT transactions_failures_say_why CHECK (
        status <> 'FAILED' OR failure_code IS NOT NULL
    ),
    CONSTRAINT transactions_failure_code_length CHECK (
        failure_code IS NULL OR length(btrim(failure_code)) BETWEEN 1 AND 64
    ),
    CONSTRAINT transactions_failure_message_length CHECK (
        failure_message IS NULL OR length(btrim(failure_message)) <= 1000
    ),
    CONSTRAINT transactions_attempt_number_is_positive CHECK (attempt_number >= 1),
    CONSTRAINT transactions_idempotency_key_length CHECK (
        length(btrim(idempotency_key)) BETWEEN 8 AND 255
    )
);

-- §9.3's R-08, as an index rather than as a promise. Two replicas that both
-- decide to settle attempt three on the same pledge produce the same key, and
-- exactly one of the inserts survives.
--
-- Partial over the settled states, so the `PENDING` row an accepted-but-undecided
-- charge writes does not stand in the way of the row that later settles it. See the
-- column for why a second `PENDING` row cannot accumulate.
CREATE UNIQUE INDEX transactions_idempotency_key_key
    ON transactions (idempotency_key)
    WHERE status IN ('SUCCEEDED', 'FAILED');

-- **One settled outcome per provider transaction.** Partial over the two states
-- that are final, so a `PENDING` row and the row that resolves it may share an
-- identifier -- which they must, since neither can be updated into the other --
-- while two successful charges under one identifier cannot exist. That is the
-- double-collection this table is here to make impossible.
CREATE UNIQUE INDEX transactions_settled_provider_key
    ON transactions (provider, provider_transaction_id)
    WHERE provider_transaction_id IS NOT NULL AND status IN ('SUCCEEDED', 'FAILED');

-- "Everything that happened to this pledge", newest first: the support read, and
-- the read §9.6's schedule uses to reconstruct what has already been tried.
CREATE INDEX transactions_pledge_idx ON transactions (pledge_id, created_at DESC)
    WHERE pledge_id IS NOT NULL;

-- "Everything that moved on this campaign", which is the payout run's question
-- and reconciliation's.
CREATE INDEX transactions_project_idx ON transactions (project_id, created_at DESC);

CREATE FUNCTION transactions_refuse_change() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'transactions is append-only; % is refused', tg_op
        USING ERRCODE = 'restrict_violation',
              HINT = 'A correction is a new row. See V41 for why a status cannot be moved.';
END;
$$;

COMMENT ON FUNCTION transactions_refuse_change() IS
    'Refuses any statement that would change or remove a transaction. §7.2: corrections are new rows.';

CREATE TRIGGER transactions_is_append_only
    BEFORE UPDATE OR DELETE OR TRUNCATE ON transactions
    FOR EACH STATEMENT EXECUTE FUNCTION transactions_refuse_change();

COMMENT ON TABLE transactions IS
    '§7.2 and §9.4: one row per call to a payment provider, insert only. Exists for attempts that moved nothing as much as for the ones that did.';
COMMENT ON COLUMN transactions.provider_transaction_id IS
    'The provider''s identifier. Unique only across settled rows, because a PENDING row and the row that resolves it share one.';
COMMENT ON COLUMN transactions.idempotency_key IS
    '§9.3''s R-08. Derived from the pledge and the attempt, so a retry of an attempt already made cannot insert a second row.';

-- ---------------------------------------------------------------------------
-- ledger_entries
-- ---------------------------------------------------------------------------

CREATE TABLE ledger_entries (
    -- `bigserial` per §7.2, and the one surrogate key in this pair. An entry has
    -- no natural identity -- two entries of one posting can differ in nothing but
    -- their account -- and the insertion order is worth keeping, because reading a
    -- posting back in the order it was written is how anybody checks it by eye.
    id             bigint         GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    -- **The unit that balances.** Not nullable and not deferrable: an entry with
    -- no transaction is money that moved for no reason anybody recorded, and it
    -- would also be invisible to the invariant below, which groups by this column.
    transaction_id uuid           NOT NULL REFERENCES transactions (id) ON DELETE NO ACTION,

    -- §7.2's vocabulary: `escrow`, `creator:{id}`, `platform_fee`, `psp_fee`,
    -- `tax_payable`, `refunds`.
    --
    -- **Text with a pattern, and not a foreign key to an accounts table.** Four of
    -- the six are singletons that no row anywhere creates or removes, and the
    -- fifth is one per creator -- so an accounts table would be a table whose
    -- entire content is derivable, kept in step by a trigger, and joined on every
    -- read of the largest table in the schema. The pattern below is what stops the
    -- typo an accounts table would otherwise be protecting against: `platform_fees`
    -- is refused, and a creator account must carry a syntactically valid
    -- identifier.
    --
    -- The cost is that `creator:{id}` is not a reference, so the database will not
    -- stop a creator being deleted out from under their balance. §17.4 anonymises
    -- rather than deleting, and `transactions.project_id` is a real reference, so
    -- the campaign the balance belongs to cannot disappear.
    account        text           NOT NULL,

    -- Debit or credit, spelled out. **Not a boolean and not a sign on the amount**:
    -- "which way did this go" is asked in every report over this table, and
    -- `WHERE is_debit` reads as a guess at the convention where `WHERE direction =
    -- 'DEBIT'` does not.
    direction      text           NOT NULL,

    -- Always positive; the direction carries the sign. A negative debit is a credit
    -- written by somebody who did not know the column existed, and it would balance
    -- against nothing.
    amount         numeric(14, 2) NOT NULL,

    -- The amount with the direction folded in, computed by PostgreSQL so it cannot
    -- disagree with the two columns it is made of. **The invariant below is a sum
    -- over this column**, which is what makes the check one aggregate rather than
    -- two conditional ones -- and what makes "this posting balances" mean exactly
    -- "these numbers add to zero".
    signed_amount  numeric(14, 2) GENERATED ALWAYS AS
                       (CASE WHEN direction = 'DEBIT' THEN amount ELSE -amount END) STORED,

    currency       text           NOT NULL,

    -- Which campaign's books this belongs to. Denormalised from the transaction,
    -- like `transactions.project_id` is from the pledge, and for the same read:
    -- every balance anybody asks for is a balance *on a campaign*, and reaching it
    -- through a join would put `transactions` in the plan of every report over the
    -- table that will grow fastest on the platform.
    project_id     uuid           NOT NULL REFERENCES projects (id) ON DELETE NO ACTION,

    created_at     timestamptz    NOT NULL DEFAULT now(),

    -- `escrow`, `platform_fee`, `psp_fee`, `tax_payable`, `refunds`, or
    -- `creator:` followed by a UUID. Anchored at both ends, so a trailing space is
    -- a different account and is refused rather than silently becoming a seventh.
    CONSTRAINT ledger_entries_account_known CHECK (
        account IN ('escrow', 'platform_fee', 'psp_fee', 'tax_payable', 'refunds')
        OR account ~ '^creator:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
    ),
    CONSTRAINT ledger_entries_direction_known CHECK (direction IN ('DEBIT', 'CREDIT')),
    -- Zero is not a movement. A posting that wants to record "no fee was charged"
    -- records it by having no `platform_fee` entry, which is the same fact without
    -- a row that every sum has to carry.
    CONSTRAINT ledger_entries_amount_is_positive CHECK (amount > 0),
    CONSTRAINT ledger_entries_currency_is_iso CHECK (currency ~ '^[A-Z]{3}$')
);

-- **The invariant.** §7.2: for every `transaction_id`, SUM(debit) = SUM(credit).
--
-- Grouped by currency as well as by transaction, and that is not decoration.
-- §21.2 refuses to convert between currencies for anything that moves money, so a
-- posting of 100 AZN of debits against 100 USD of credits is not a balanced
-- posting -- it is two unbalanced ones that a currency-blind sum would report as
-- correct.
CREATE FUNCTION ledger_entries_must_balance() RETURNS trigger
    LANGUAGE plpgsql AS $$
DECLARE
    offending record;
BEGIN
    SELECT e.currency AS currency, sum(e.signed_amount) AS net
      INTO offending
      FROM ledger_entries e
     WHERE e.transaction_id = new.transaction_id
     GROUP BY e.currency
    HAVING sum(e.signed_amount) <> 0
     LIMIT 1;

    IF found THEN
        RAISE EXCEPTION
            'Ledger transaction % does not balance in %: debits exceed credits by %',
            new.transaction_id, offending.currency, offending.net
            USING ERRCODE = 'check_violation',
                  HINT = 'Every posting is written whole. See V41 and Ledger#post.';
    END IF;

    RETURN NULL;
END;
$$;

COMMENT ON FUNCTION ledger_entries_must_balance() IS
    '§7.2''s double-entry invariant, per transaction and per currency. Deferred to commit, because the first entry of a balanced pair is unbalanced on its own.';

-- Deferred to commit, so a posting may insert its entries one row at a time. See
-- the header for why an ordinary trigger cannot express this and why the failure
-- therefore surfaces at COMMIT rather than at the offending INSERT.
CREATE CONSTRAINT TRIGGER ledger_entries_balance
    AFTER INSERT ON ledger_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledger_entries_must_balance();

CREATE FUNCTION ledger_entries_refuse_change() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'ledger_entries is append-only; % is refused', tg_op
        USING ERRCODE = 'restrict_violation',
              HINT = 'A correction is a reversing posting, never an edit. See V41.';
END;
$$;

COMMENT ON FUNCTION ledger_entries_refuse_change() IS
    'Refuses any statement that would change or remove a ledger entry. A ledger that can be edited is not one.';

CREATE TRIGGER ledger_entries_is_append_only
    BEFORE UPDATE OR DELETE OR TRUNCATE ON ledger_entries
    FOR EACH STATEMENT EXECUTE FUNCTION ledger_entries_refuse_change();

-- The invariant's own read, and reconciliation's: every entry of one posting.
CREATE INDEX ledger_entries_transaction_idx ON ledger_entries (transaction_id);

-- "What is the balance of this account on this campaign", which is the only
-- question the payout run asks and the only one §9.5's diagram can be checked
-- against. Account first, because a balance is asked for one account across a
-- campaign far more often than for one campaign across accounts.
CREATE INDEX ledger_entries_account_idx ON ledger_entries (account, project_id, created_at);

COMMENT ON TABLE ledger_entries IS
    '§7.2''s double entry (#62). Append-only, and balanced per transaction and per currency by a deferred constraint trigger.';
COMMENT ON COLUMN ledger_entries.account IS
    'escrow, creator:{uuid}, platform_fee, psp_fee, tax_payable, or refunds. Text with a pattern rather than a reference; see V41.';
COMMENT ON COLUMN ledger_entries.signed_amount IS
    'The amount with the direction folded in. The invariant is a sum over this column.';
