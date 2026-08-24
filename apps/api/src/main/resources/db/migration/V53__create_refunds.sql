-- §4.11's AD-06 (#307) and the engine behind it (#67): full and partial refunds
-- with reason codes and ledger reversal.
--
-- ---------------------------------------------------------------------------
-- Reverse:
--   DROP TABLE IF EXISTS refunds;
--
--   The `transactions` rows of type REFUND and the ledger entries behind them
--   are NOT removed, and must not be: they are the record that money moved, and
--   V41 makes both append-only. What is lost is the reason code, who approved
--   it, and the link back to the pledge -- so the money is still accounted for
--   and nobody can say why. Do not reverse this on a deployment that has issued
--   one.
-- ---------------------------------------------------------------------------
--
-- ---------------------------------------------------------------------------
-- WHY A TABLE, WHEN transactions ALREADY HAS A REFUND TYPE
-- ---------------------------------------------------------------------------
--
-- V41 anticipated this: `transactions.type` already permits REFUND, and the
-- ledger already has a `refunds` account. So the money half needs no new table.
--
-- What it needs is the half `transactions` deliberately does not carry. That
-- table is a log of provider calls -- what was asked, what was answered, when.
-- A refund additionally has a *decision*: a reason code somebody chose, a
-- justification they typed, an approval, and a state that is not the provider's
-- (`REQUESTED` exists before any call is made, and `FAILED` has to be
-- retryable). Putting those on `transactions` would either make that table
-- mutable -- which V41 spends a page refusing -- or scatter the decision across
-- several immutable rows nobody can reassemble.
--
-- So: this row is the decision, and it points at the transaction that carried it
-- out once one exists.
--
-- ---------------------------------------------------------------------------
-- WHY reason IS A CLOSED SET AND detail IS FREE TEXT
-- ---------------------------------------------------------------------------
--
-- §4.11 says "with reason codes", and the reason it says so is that refunds are
-- counted. "How many refunds were because the campaign was suspended" is a
-- question with a number for an answer, and free text makes it a question with a
-- spreadsheet for an answer. The code is the countable half.
--
-- The detail is required anyway, because a code is never the whole story and the
-- person who reads this row in six months is answering a complaint rather than
-- filling in a chart.
--
-- ---------------------------------------------------------------------------
-- WHY A PARTIAL REFUND IS AN AMOUNT AND NOT A PERCENTAGE
-- ---------------------------------------------------------------------------
--
-- A percentage has to be multiplied by something to become money, and the
-- rounding of that multiplication is then a rule this table does not state. An
-- amount is the money. `RefundService` computes what a "full" refund is and
-- writes the resulting amount here, so the row records what was actually sent.
--
-- ---------------------------------------------------------------------------
-- THE INVARIANT THAT IS NOT A CONSTRAINT
-- ---------------------------------------------------------------------------
--
-- "The refunds against a pledge may not exceed what was collected on it" cannot
-- be written as a CHECK, because it is a statement about a set of rows in this
-- table joined against a set of rows in another. It is enforced in
-- `RefundService` under a row lock on the pledge, and asserted by
-- `RefundOverdraftTests`. A partial unique index cannot express it either, which
-- is why this comment exists rather than a constraint somebody would later
-- assume was doing the work.
-- ---------------------------------------------------------------------------

CREATE TABLE refunds (
    id uuid PRIMARY KEY,

    -- What is being refunded. NO ACTION for `transactions.pledge_id`'s reason: a
    -- pledge that has been refunded may not be deleted out from under the record
    -- of the refund.
    pledge_id uuid NOT NULL REFERENCES pledges (id) ON DELETE NO ACTION,

    project_id uuid NOT NULL REFERENCES projects (id) ON DELETE NO ACTION,

    -- The charge this reverses. Nullable only because a refund can be requested
    -- against a pledge whose charge row is being located at the moment of
    -- writing; `RefundService` always sets it.
    charge_transaction_id uuid REFERENCES transactions (id) ON DELETE NO ACTION,

    -- The provider call that carried it out, once there has been one. Null while
    -- the refund is REQUESTED, and null forever on one that failed before the
    -- provider was reached.
    refund_transaction_id uuid REFERENCES transactions (id) ON DELETE NO ACTION,

    amount numeric(14, 2) NOT NULL
        CONSTRAINT refunds_amount_is_positive CHECK (amount > 0),

    currency text NOT NULL
        CONSTRAINT refunds_currency_is_iso CHECK (currency ~ '^[A-Z]{3}$'),

    -- Whether this was meant to be the whole thing. Stored rather than derived
    -- from comparing the amount to the pledge, because "we intended a full
    -- refund" and "the amount happened to equal the pledge" are different facts
    -- and only the first one is a decision.
    full_refund boolean NOT NULL,

    reason text NOT NULL
        CONSTRAINT refunds_reason_known CHECK (reason IN (
            -- The backer asked and the campaign had not shipped.
            'BACKER_REQUEST',
            -- §6.1's suspension, or a creator cancelling. The pledge module
            -- already ends the pledge; this is the money following it.
            'CAMPAIGN_HALTED',
            -- The campaign missed its goal and something was collected anyway.
            'CAMPAIGN_FAILED',
            -- The creator did not deliver what was promised.
            'FULFILMENT_FAILURE',
            -- Charged twice for the same thing.
            'DUPLICATE_CHARGE',
            -- The platform's mistake, whatever it was. Deliberately present: a
            -- taxonomy with no "our fault" bucket gets one anyway, spelled as
            -- whichever of the others is closest.
            'PLATFORM_ERROR',
            -- Answering a dispute before the provider decides it. See V54.
            'DISPUTE_CONCEDED',
            'FRAUD')),

    detail text NOT NULL
        CONSTRAINT refunds_detail_present CHECK (length(btrim(detail)) BETWEEN 1 AND 2000),

    -- REQUESTED -> SUCCEEDED, or -> FAILED, from which it may be retried into a
    -- new row. There is no CANCELLED: a refund that has not been sent is deleted
    -- by nobody and simply stays REQUESTED, because a state meaning "we changed
    -- our mind" would be indistinguishable from one meaning "the job has not run
    -- yet".
    state text NOT NULL DEFAULT 'REQUESTED'
        CONSTRAINT refunds_state_known CHECK (state IN ('REQUESTED', 'SUCCEEDED', 'FAILED')),

    failure_code text,

    failure_message text,

    CONSTRAINT refunds_failure_matches_state CHECK (
        state = 'FAILED' OR (failure_code IS NULL AND failure_message IS NULL)),

    CONSTRAINT refunds_settled_has_transaction CHECK (
        state <> 'SUCCEEDED' OR refund_transaction_id IS NOT NULL),

    -- Who decided. RESTRICT rather than SET NULL: a refund with no author is one
    -- nobody can be asked about, and §17 requires the opposite.
    requested_by uuid NOT NULL REFERENCES users (id) ON DELETE RESTRICT,

    requested_at timestamptz NOT NULL DEFAULT now(),

    settled_at timestamptz,

    CONSTRAINT refunds_settled_at_matches_state CHECK (
        (state = 'REQUESTED') = (settled_at IS NULL)),

    -- The idempotency key the console sent. CLAUDE.md: every payment mutation is
    -- idempotent. A retried request that reached the provider once must not
    -- reach it twice, and the unique index below is what makes the second call
    -- return the first result instead of sending more money.
    idempotency_key text NOT NULL
        CONSTRAINT refunds_idempotency_key_shape CHECK (length(idempotency_key) BETWEEN 8 AND 200)
);

-- The idempotency guarantee. Unique across the table rather than per pledge: a
-- key is generated by the client per intent, and two intents that share a key
-- are the same intent even if somebody has since retyped the pledge.
CREATE UNIQUE INDEX refunds_idempotency_key_unique ON refunds (idempotency_key);

-- "What has been refunded against this pledge", which is the read the overdraft
-- check makes under a lock before every refund.
CREATE INDEX refunds_by_pledge ON refunds (pledge_id, requested_at DESC);

-- The console's list, newest first, filterable by state.
CREATE INDEX refunds_by_state ON refunds (state, requested_at DESC);

CREATE INDEX refunds_by_project ON refunds (project_id, requested_at DESC);

COMMENT ON TABLE refunds IS
    'The decision behind a refund: reason code, author, state (#67, #307, AD-06). The money is in transactions/ledger_entries.';
