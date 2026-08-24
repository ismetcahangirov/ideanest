-- §4.11's AD-07 (#308) and the intake behind it (#68): a chargeback, from the
-- provider's notification to the outcome.
--
-- ---------------------------------------------------------------------------
-- Reverse:
--   DROP TABLE IF EXISTS dispute_evidence;
--   DROP TABLE IF EXISTS disputes;
--
--   Order matters. The `transactions` rows of type CHARGEBACK and
--   CHARGEBACK_REVERSAL survive, as they must -- the money moved. What is lost
--   is the case: the provider's reason, the evidence that was submitted, and the
--   deadline it was submitted against. A dispute in progress becomes unanswerable
--   and is then lost by default, which costs the platform the disputed amount.
-- ---------------------------------------------------------------------------
--
-- ---------------------------------------------------------------------------
-- WHY THE PLATFORM CANNOT TREAT A CHARGEBACK AS A REFUND
-- ---------------------------------------------------------------------------
--
-- Both end with the backer having their money back, and that is where the
-- similarity stops.
--
--   * A refund is the platform's decision. A chargeback is the card network's,
--     and the platform is a respondent with a deadline.
--   * A refund succeeds or fails once. A chargeback can be lost, then won on
--     representment, then lost again on a second presentment -- so the state
--     machine has a cycle a refund does not.
--   * A chargeback carries a fee the provider charges regardless of outcome, and
--     that fee is the platform's cost rather than the backer's or the creator's.
--
-- Modelling one as the other would produce a `refunds` row nobody requested with
-- a reason code meaning "somebody else decided", and would lose the deadline --
-- which is the only field on this table that anybody is ever paged about.
--
-- ---------------------------------------------------------------------------
-- WHY evidence IS ITS OWN TABLE
-- ---------------------------------------------------------------------------
--
-- Evidence is submitted in pieces, over days, by different people, and each
-- piece is a document with a provider reference of its own. A jsonb column on
-- the dispute would make "which of these did the provider acknowledge" a
-- question about an array index, which is the same argument V47 makes for FAQ
-- entries and reaches the same conclusion.
--
-- ---------------------------------------------------------------------------
-- WHY THERE IS NO FOREIGN KEY TO refunds
-- ---------------------------------------------------------------------------
--
-- Conceding a dispute writes a `refunds` row with reason DISPUTE_CONCEDED, and
-- it would be natural to point at it from here. It is deliberately the other way
-- round -- the refund names nothing, and the dispute names the refund's pledge
-- -- because a concession sometimes happens *outside* the platform: the provider
-- accepts the reversal and the money is gone before anybody presses anything.
-- A mandatory link would make that ordinary case unrecordable, and a nullable
-- one is a column that is null on the rows anybody actually looks at.
-- ---------------------------------------------------------------------------

CREATE TABLE disputes (
    id uuid PRIMARY KEY,

    -- The charge being disputed. Not nullable: a dispute with no charge behind it
    -- is a notification about somebody else's transaction.
    charge_transaction_id uuid NOT NULL REFERENCES transactions (id) ON DELETE NO ACTION,

    pledge_id uuid NOT NULL REFERENCES pledges (id) ON DELETE NO ACTION,

    project_id uuid NOT NULL REFERENCES projects (id) ON DELETE NO ACTION,

    provider text NOT NULL,

    -- The provider's identifier for the case. Unique, because a webhook is
    -- delivered more than once by design (V43) and the second delivery must find
    -- the case rather than open a second one.
    provider_dispute_id text NOT NULL,

    amount numeric(14, 2) NOT NULL
        CONSTRAINT disputes_amount_is_positive CHECK (amount > 0),

    currency text NOT NULL
        CONSTRAINT disputes_currency_is_iso CHECK (currency ~ '^[A-Z]{3}$'),

    -- What the provider charges for handling it, win or lose. Separate from
    -- `amount` because it is the platform's cost and lands on a different ledger
    -- account; folding it in would overstate what the backer disputed.
    fee numeric(14, 2) NOT NULL DEFAULT 0
        CONSTRAINT disputes_fee_is_not_negative CHECK (fee >= 0),

    -- The network's category, as the provider spells it, normalised to upper
    -- snake case by the adapter. Not a closed set: every network has its own
    -- list, they change, and a CHECK here would turn a new reason code into a
    -- webhook that cannot be recorded -- which is the one failure this table
    -- must not have, because the deadline runs whether or not we stored it.
    reason_code text NOT NULL,

    -- OPEN: notified, not yet answered.
    -- UNDER_REVIEW: evidence submitted, waiting on the network.
    -- WON: the charge stands.
    -- LOST: the money is gone.
    -- CONCEDED: we chose not to contest it.
    -- The cycle the header describes is OPEN -> UNDER_REVIEW -> LOST -> OPEN,
    -- which is why there is no constraint asserting that a state only moves
    -- forward.
    state text NOT NULL DEFAULT 'OPEN'
        CONSTRAINT disputes_state_known CHECK (state IN (
            'OPEN', 'UNDER_REVIEW', 'WON', 'LOST', 'CONCEDED')),

    -- When the provider stops accepting evidence. The one column anybody is
    -- paged about, and nullable only because not every provider sends one.
    evidence_due_at timestamptz,

    opened_at timestamptz NOT NULL DEFAULT now(),

    resolved_at timestamptz,

    CONSTRAINT disputes_resolved_matches_state CHECK (
        (state IN ('WON', 'LOST', 'CONCEDED')) = (resolved_at IS NOT NULL)),

    -- Whoever last moved it. Null while nobody has touched it, which is exactly
    -- the set the queue shows first.
    handled_by uuid REFERENCES users (id) ON DELETE SET NULL,

    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX disputes_provider_case_unique ON disputes (provider, provider_dispute_id);

-- The queue: what is open, soonest deadline first. NULLS LAST so a provider that
-- sends no deadline does not sort above one that does -- a case with a date is
-- more urgent than a case without one, not less.
CREATE INDEX disputes_open_queue
    ON disputes (evidence_due_at ASC NULLS LAST, opened_at ASC)
    WHERE state IN ('OPEN', 'UNDER_REVIEW');

CREATE INDEX disputes_by_project ON disputes (project_id, opened_at DESC);

CREATE TABLE dispute_evidence (
    id uuid PRIMARY KEY,

    dispute_id uuid NOT NULL REFERENCES disputes (id) ON DELETE CASCADE,

    kind text NOT NULL
        CONSTRAINT dispute_evidence_kind_known CHECK (kind IN (
            'RECEIPT', 'SHIPPING_PROOF', 'COMMUNICATION', 'TERMS_ACCEPTANCE',
            'REFUND_POLICY', 'ACTIVITY_LOG', 'OTHER')),

    -- What was said, or a description of the attached document.
    description text NOT NULL
        CONSTRAINT dispute_evidence_description_present CHECK (
            length(btrim(description)) BETWEEN 1 AND 5000),

    -- A media object, when the evidence is a file. Deliberately a bare uuid with
    -- no foreign key: the media module owns its own table and §17.2 lets it prune,
    -- and a dispute must remain readable after a document behind it has aged out.
    media_id uuid,

    -- The provider's acknowledgement, once submitted. Null while the piece is
    -- assembled but not sent, which is the state most rows are in while somebody
    -- is working a case.
    submitted_at timestamptz,

    provider_evidence_id text,

    created_at timestamptz NOT NULL DEFAULT now(),

    created_by uuid NOT NULL REFERENCES users (id) ON DELETE RESTRICT
);

CREATE INDEX dispute_evidence_by_dispute ON dispute_evidence (dispute_id, created_at ASC);

COMMENT ON TABLE disputes IS
    'Chargebacks: the provider notification, the deadline, and the outcome (#68, #308, AD-07).';
