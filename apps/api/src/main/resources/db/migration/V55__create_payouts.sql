-- §4.11's AD-05 (#306) and the calculation behind it (#69): what a creator is
-- owed, held, approved twice, and sent.
--
-- ---------------------------------------------------------------------------
-- Reverse:
--   DROP TABLE IF EXISTS payout_approvals;
--   DROP TABLE IF EXISTS payouts;
--
--   Order matters. The `transactions` rows of type PAYOUT survive and must: they
--   are the record that money left. What is lost is the calculation -- which
--   pledges were included, what was deducted and under which fee schedule -- and
--   with it the ability to answer a creator asking why the figure is what it is.
--   Also lost: who approved it, which §17 requires be answerable.
-- ---------------------------------------------------------------------------
--
-- ---------------------------------------------------------------------------
-- WHY THE CALCULATION IS STORED AND NOT RECOMPUTED
-- ---------------------------------------------------------------------------
--
-- A payout is derivable: sum the collected pledges, subtract the fees, subtract
-- the refunds. Deriving it on every read is tempting and is wrong for the same
-- reason `fee_schedules` is a table -- the inputs move. A pledge refunded after
-- a payout was calculated changes the sum; a fee schedule that came into force
-- last week changes the deduction. Recomputing would produce a different figure
-- from the one that was approved, and the approval would then be an approval of
-- nothing in particular.
--
-- So the figures are frozen at calculation, the fee schedule that produced them
-- is named on the row, and a change afterwards produces a *new* payout rather
-- than a different answer to the old one.
--
-- ---------------------------------------------------------------------------
-- WHY payout_approvals IS A TABLE AND NOT TWO COLUMNS
-- ---------------------------------------------------------------------------
--
-- §4.11 asks for "dual approval above a threshold". The obvious shape is
-- `approved_by_1` and `approved_by_2`, and it is wrong in three ways that all
-- show up in the first month:
--
--   * The threshold is configuration, so a payout needs one approval or two
--     depending on a number that can change between the first approval and the
--     second. Two columns cannot say "this one needed two"; a row count against
--     a stored `approvals_required` can.
--   * The rule is that the approvers are *different people*. With two columns
--     that is a CHECK comparing them, which silently passes when the second is
--     null. With rows it is a unique index on (payout, approver), which cannot.
--   * An approval is withdrawable before the payout is sent, and withdrawing
--     `approved_by_1` when `approved_by_2` is set would leave a gap somebody has
--     to decide how to close.
--
-- ---------------------------------------------------------------------------
-- WHY THE HOLD IS A COLUMN AND NOT A JOB THAT WAITS
-- ---------------------------------------------------------------------------
--
-- §9 gives a payout a hold period after the campaign closes, so that chargebacks
-- and refunds land before the money leaves. Storing `payable_at` and letting the
-- queue filter on it makes the hold visible on the screen -- a member of staff
-- can see that a payout exists and when it becomes payable -- where a job that
-- simply does not create the row until the hold expires makes the same campaign
-- look as though nothing is owed.
-- ---------------------------------------------------------------------------

CREATE TABLE payouts (
    id uuid PRIMARY KEY,

    project_id uuid NOT NULL REFERENCES projects (id) ON DELETE NO ACTION,

    -- Who is paid. Denormalised from the campaign, because a campaign's creator
    -- is a mutable fact and the payout was calculated for the person who held it
    -- at the time.
    creator_id uuid NOT NULL REFERENCES users (id) ON DELETE NO ACTION,

    -- What was collected, before anything is taken off. The sum of the CHARGE
    -- transactions this payout covers.
    gross_amount numeric(14, 2) NOT NULL
        CONSTRAINT payouts_gross_is_not_negative CHECK (gross_amount >= 0),

    platform_fee numeric(14, 2) NOT NULL DEFAULT 0
        CONSTRAINT payouts_platform_fee_is_not_negative CHECK (platform_fee >= 0),

    processing_fee numeric(14, 2) NOT NULL DEFAULT 0
        CONSTRAINT payouts_processing_fee_is_not_negative CHECK (processing_fee >= 0),

    -- What was withheld for tax. Zero until §9's tax rules are built; the column
    -- exists now for `transactions.type`'s reason -- adding it later is a
    -- migration over a table that will by then be a financial record.
    tax_withheld numeric(14, 2) NOT NULL DEFAULT 0
        CONSTRAINT payouts_tax_is_not_negative CHECK (tax_withheld >= 0),

    -- Refunds already issued against the pledges in this payout. Subtracted
    -- rather than netted into `gross_amount`, so the creator can be shown what
    -- was collected and what came back as two numbers.
    refunded_amount numeric(14, 2) NOT NULL DEFAULT 0
        CONSTRAINT payouts_refunded_is_not_negative CHECK (refunded_amount >= 0),

    -- What actually leaves. Stored rather than generated, because a generated
    -- column would recompute if any input were ever corrected -- which is exactly
    -- the drift this table exists to prevent -- and because `PayoutCalculator`
    -- rounds it once, deliberately, rather than letting the database round it
    -- differently.
    net_amount numeric(14, 2) NOT NULL
        CONSTRAINT payouts_net_is_not_negative CHECK (net_amount >= 0),

    currency text NOT NULL
        CONSTRAINT payouts_currency_is_iso CHECK (currency ~ '^[A-Z]{3}$'),

    -- The schedule the deductions were computed under. RESTRICT, so a fee
    -- schedule that priced a payout cannot be deleted out from under it.
    fee_schedule_id uuid REFERENCES fee_schedules (id) ON DELETE RESTRICT,

    -- CALCULATED: the figure exists and the hold may still be running.
    -- PENDING_APPROVAL: payable, waiting on signatures.
    -- APPROVED: signed off, waiting for the sender.
    -- PAID: the provider took it.
    -- FAILED: the provider refused; retryable into a new payout.
    -- CANCELLED: staff withdrew it before it was sent.
    state text NOT NULL DEFAULT 'CALCULATED'
        CONSTRAINT payouts_state_known CHECK (state IN (
            'CALCULATED', 'PENDING_APPROVAL', 'APPROVED', 'PAID', 'FAILED', 'CANCELLED')),

    -- When the hold expires. See the header on why this is a column.
    payable_at timestamptz NOT NULL,

    -- How many distinct approvers this payout needs, frozen at calculation from
    -- the configured threshold. See the header: the threshold can move between
    -- the first approval and the second, and a payout has to know which rule it
    -- was created under.
    approvals_required smallint NOT NULL DEFAULT 1
        CONSTRAINT payouts_approvals_required_sane CHECK (approvals_required BETWEEN 1 AND 3),

    payout_transaction_id uuid REFERENCES transactions (id) ON DELETE NO ACTION,

    CONSTRAINT payouts_paid_has_transaction CHECK (
        state <> 'PAID' OR payout_transaction_id IS NOT NULL),

    failure_code text,

    failure_message text,

    CONSTRAINT payouts_failure_matches_state CHECK (
        state = 'FAILED' OR (failure_code IS NULL AND failure_message IS NULL)),

    calculated_at timestamptz NOT NULL DEFAULT now(),

    sent_at timestamptz,

    CONSTRAINT payouts_sent_matches_state CHECK (
        (state IN ('PAID', 'FAILED')) = (sent_at IS NOT NULL)),

    -- CLAUDE.md: every payment mutation is idempotent. The send is keyed on this.
    idempotency_key text NOT NULL
        CONSTRAINT payouts_idempotency_key_shape CHECK (length(idempotency_key) BETWEEN 8 AND 200)
);

CREATE UNIQUE INDEX payouts_idempotency_key_unique ON payouts (idempotency_key);

-- One live payout per campaign. A campaign may be paid more than once over its
-- life -- a late pledge collected after the first payout produces a second -- so
-- this is partial over the states that are still in flight rather than a unique
-- key on `project_id`. Two payouts in flight for one campaign is how a creator
-- gets paid twice for the same pledges.
CREATE UNIQUE INDEX payouts_one_in_flight_per_project
    ON payouts (project_id)
    WHERE state IN ('CALCULATED', 'PENDING_APPROVAL', 'APPROVED');

-- The queue: what is payable now, oldest first.
CREATE INDEX payouts_queue
    ON payouts (payable_at ASC, calculated_at ASC)
    WHERE state IN ('CALCULATED', 'PENDING_APPROVAL', 'APPROVED');

CREATE INDEX payouts_by_creator ON payouts (creator_id, calculated_at DESC);

CREATE TABLE payout_approvals (
    payout_id uuid NOT NULL REFERENCES payouts (id) ON DELETE CASCADE,

    approver_id uuid NOT NULL REFERENCES users (id) ON DELETE RESTRICT,

    approved_at timestamptz NOT NULL DEFAULT now(),

    note text
        CONSTRAINT payout_approvals_note_length CHECK (note IS NULL OR length(note) <= 2000),

    -- The dual-approval rule, as a key rather than as a CHECK that a null would
    -- pass. Two rows for one payout are two different people by construction.
    CONSTRAINT payout_approvals_pkey PRIMARY KEY (payout_id, approver_id)
);

COMMENT ON TABLE payouts IS
    'What a creator is owed, frozen at calculation, held, and approved (#69, #306, AD-05).';

COMMENT ON TABLE payout_approvals IS
    'One signature. Dual approval is two rows, which cannot be the same person (#69).';
