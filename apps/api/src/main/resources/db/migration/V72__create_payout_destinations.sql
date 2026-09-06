-- Where a creator's money goes, supplied by the creator. Part of issue #432.
--
-- ---------------------------------------------------------------------------
-- Reverse:
--   DROP TABLE IF EXISTS payout_destinations;
--
--   Lossy, and worth naming. The row holds a provider token the platform cannot
--   re-derive: it was issued once, to a creator who entered their bank details
--   at the provider, and dropping the table means every creator re-enters them.
--   Export before reversing.
--
--   Safe under rolling deployment in one direction only, and the asymmetry is
--   the point. A release running WITHOUT the code that ships with this migration
--   sends payouts the old way -- from a destination typed by a member of staff --
--   and a release running WITH it refuses to send a payout whose creator has no
--   verified destination. So a rollback re-opens the hole rather than breaking,
--   and a roll-forward holds payouts rather than misdirecting them. Both failure
--   modes are the safe one for their direction.
--
--   There are no production payouts to strand. §9.4's start-up check means no
--   adapter is configured anywhere, `CollectionRun` refuses without one, and
--   `NoPayoutProviderException` is what a send has answered since #69 shipped.
-- ---------------------------------------------------------------------------
--
-- ---------------------------------------------------------------------------
-- WHAT THIS REPLACES, AND WHY IT WAS WRONG
-- ---------------------------------------------------------------------------
--
-- Until now `PayoutController.SendRequest` was one field:
--
--   public record SendRequest(@NotBlank @Size(max = 200) String destinationReference) {}
--
-- A member of staff typed a destination at the moment of sending. Nothing
-- verified it, no creator supplied it, and a typo was money sent to a stranger.
--
-- The controller's own comment called that "the honest shape of" a real gap, and
-- it was: there was no payout-destination schema, and a bank reference stored in
-- a table nobody had designed would have been worse than one that was typed.
-- This is the table.
--
-- The deeper problem was not the typo. §4.11 requires two signatures above a
-- threshold, on the principle that one person should not move money alone -- and
-- a destination typed by one of those two approvers at the moment of sending
-- defeats it entirely. The approvals were of an *amount*. The destination was
-- chosen afterwards, by one person, unreviewed.
--
-- ---------------------------------------------------------------------------
-- THE PLATFORM STILL DOES NOT STORE AN IBAN
-- ---------------------------------------------------------------------------
--
-- `PayoutRequest.destinationReference` is documented as opaque to this service:
--
--   > bank details are the provider's to hold, and a platform that stored an
--   > IBAN would have acquired a second class of sensitive data with none of the
--   > SAQ A reasoning behind the first.
--
-- That is unchanged and this table does not overturn it. `reference` below is a
-- provider-issued token, exactly like `stored_cards.token`. What changes is only
-- whose it is and how it got here: obtained once from the creator, verified, and
-- filed against their account -- instead of a string typed into a form.
--
-- `holder_name` is the one piece of the bank's answer that is stored, because it
-- is the thing being checked. It is a name, which the platform already holds in
-- `creator_legal_subjects.legal_name` and in a SIMA certificate subject, so it
-- adds no class of data that is not already here.
--
-- `display_hint` is what a person recognises the account by -- the last few
-- characters, as the provider chose to mask them. Constrained below to refuse
-- anything long enough to be an IBAN.
--
-- ---------------------------------------------------------------------------
-- ONE DESTINATION PER CREATOR, AND REPLACING IT RE-VERIFIES
-- ---------------------------------------------------------------------------
--
-- `creator_legal_subjects`' shape and argument. A creator is paid to one place
-- at a time; a second row would mean the platform had to choose between them and
-- there is no column that could say which.
--
-- Replacement is an UPDATE that clears `verified_at`, `verified_by` and
-- `verification_method` back to null. That is enforced below by a constraint
-- rather than left to the service, because the failure it guards against is
-- silent: a creator whose destination was verified in March swapping the token
-- in June and inheriting March's verification is precisely the fraud this whole
-- issue exists to stop, and it would look like nothing at all in the row.
--
-- ---------------------------------------------------------------------------
-- THE PROVIDER IS PART OF THE KEY TO THE TOKEN, NOT DECORATION
-- ---------------------------------------------------------------------------
--
-- A token means nothing to a provider that did not issue it. §9.3 ends with
-- "integrate at least two providers", so the platform will one day hold tokens
-- from two -- and a payout sent through Payriff using an Epoint token is a
-- refusal at best. `provider` says whose token this is, and the send refuses
-- when it is not the configured one rather than passing it along.
--
-- The CHECK below repeats `ProviderName`'s values. `PayoutDestinationSchemaTests`
-- asserts the two sets are equal, so adding a provider to the enum without
-- touching the constraint fails a test rather than a production insert.
--
-- ---------------------------------------------------------------------------
-- WHO VERIFIES, AND WHY IT IS NOT WHOEVER SENDS
-- ---------------------------------------------------------------------------
--
-- V66 already decided this, one issue early, and the reasoning is quoted here so
-- that a reader of this table does not have to find it:
--
--   > FINANCE already holds the authority to initiate a payout. The same role
--   > deciding both *where* money goes and *that the destination is correct* is
--   > one person holding both halves, which is the arrangement 4.11's dual
--   > approval exists to prevent.
--
-- So `verified_by` holds somebody with COMPLIANCE's VERIFY_PAYOUT_DESTINATION,
-- never FINANCE's APPROVE_PAYOUT, and the two are different people by
-- construction rather than by convention.
--
-- `verified_by` is ON DELETE RESTRICT, following `compliance_overrides.granted_by`:
-- a verification whose verifier had been erased would be a statement nobody made.
-- `user_id` CASCADEs, following every other reference to `users` in this schema --
-- 17.4's erasure, and the practical reason memory keeps re-teaching, which is
-- that a non-cascading foreign key to `users` breaks the suites that truncate it.
--
-- ---------------------------------------------------------------------------
-- WHICH MECHANISM VERIFIED IT IS RECORDED, AND TWO OF THE THREE WAIT ON #422
-- ---------------------------------------------------------------------------
--
-- #432 names two ways to prove an account belongs to the creator:
--
--   A. The provider returns a verified account holder name, if sub-merchant
--      onboarding (R-10) includes bank-account validation. Cheapest, strongest,
--      and only available if Epoint says so in writing -- which is #422.
--   B. A reverse micro-transfer: the creator sends 0.01 AZN from the account with
--      a generated reference, and the incoming statement line carries the sender's
--      IBAN and registered name, so the bank does the verification.
--
-- Neither can be executed today: A needs #422's answer and B needs a statement
-- feed. What exists now is the manual form of B -- a compliance reviewer looks at
-- evidence and attests -- which is what #432 describes B degenerating into at
-- launch volumes ("a manual or semi-automated statement reconciliation, which at
-- launch volumes is a few minutes a day").
--
-- All three are values here rather than one, because the column's job is to
-- answer "how do we know this account is theirs" years later, and a row that
-- said only "verified" would answer it with the least useful of the three
-- possible truths. STAFF_ATTESTED is the one the code can write today; the other
-- two are written by the mechanisms #422 unblocks, and a row carrying one of them
-- is a row a reader can trust more, which is the distinction worth storing.
--
-- ---------------------------------------------------------------------------
-- NAME MISMATCH IS A STATE AND NOT A REFUSAL AT WRITE TIME
-- ---------------------------------------------------------------------------
--
-- The holder's name is matched against #430's `legal_name`. When they disagree
-- the row is stored with `state = 'NAME_MISMATCH'` rather than rejected, because
-- the two ways of refusing lead somewhere different: a rejected write leaves the
-- creator staring at a form with no record that anything happened, and a stored
-- mismatch is a row a compliance reviewer can see, ask about, and resolve. V58
-- already has the vocabulary -- `RejectionReason.MISMATCHED_NAME` -- and two
-- vocabularies for one idea is one too many.
--
-- A creator who has recorded no legal subject at all cannot mismatch: there is
-- nothing to compare against, so the row is AWAITING_VERIFICATION and the
-- submission gate (#430, #426) is what asks for the subject.

CREATE TABLE payout_destinations (
    -- The creator, and the primary key. One destination at a time.
    user_id uuid PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,

    -- Whose token this is. Repeated from `ProviderName`; the schema test keeps
    -- the two in step.
    provider text NOT NULL
        CONSTRAINT payout_destinations_provider_known CHECK (provider IN ('PAYRIFF', 'EPOINT', 'AZERICARD')),

    -- The provider's token for the creator's account. Opaque here, exactly like
    -- `stored_cards.token`, and never logged -- `PayoutRequest.toString` already
    -- redacts the value this becomes.
    reference text NOT NULL
        CONSTRAINT payout_destinations_reference_present CHECK (length(btrim(reference)) BETWEEN 1 AND 200),

    -- What the provider or the bank says the account holder is called. The
    -- subject of the three-way match with `creator_legal_subjects.legal_name`
    -- and #429's certificate subject.
    holder_name text NOT NULL
        CONSTRAINT payout_destinations_holder_present CHECK (length(btrim(holder_name)) BETWEEN 1 AND 200),

    -- How a person recognises the account: a masked tail, as the provider masked
    -- it. Twelve characters is comfortably more than a mask and comfortably less
    -- than an IBAN, which is the point of the ceiling.
    display_hint text
        CONSTRAINT payout_destinations_hint_is_a_hint CHECK (
            display_hint IS NULL OR length(btrim(display_hint)) BETWEEN 1 AND 12),

    -- AWAITING_VERIFICATION -- recorded, nobody has confirmed it is theirs.
    -- NAME_MISMATCH         -- the holder's name is not the creator's legal name.
    -- REJECTED              -- a reviewer refused it, with a reason.
    -- VERIFIED              -- somebody or something confirmed it, and said which.
    --
    -- WAIVED is deliberately not here. An override is #436's row with an expiry
    -- on it, and copying its effect into a column here would be a second place
    -- for the waiver to live and the place that never expires.
    state text NOT NULL
        CONSTRAINT payout_destinations_state_known CHECK (
            state IN ('AWAITING_VERIFICATION', 'NAME_MISMATCH', 'REJECTED', 'VERIFIED')),

    -- Only for REJECTED, and from V58's closed set for the reason V58 gives: the
    -- creator is shown it, so it must be a value the product has words for in
    -- each of 21.1's four languages.
    rejection_reason text
        CONSTRAINT payout_destinations_rejection_known CHECK (
            rejection_reason IS NULL
                OR rejection_reason IN (
                    'UNREADABLE', 'EXPIRED_DOCUMENT', 'MISMATCHED_NAME', 'INCOMPLETE', 'SUSPECTED_FORGERY')),

    -- How it was verified. See the header: two of the three wait on #422.
    verification_method text
        CONSTRAINT payout_destinations_method_known CHECK (
            verification_method IS NULL
                OR verification_method IN ('PROVIDER_ACCOUNT_HOLDER', 'MICRO_TRANSFER', 'STAFF_ATTESTED')),

    verified_at timestamptz,

    -- RESTRICT, not CASCADE. See the header.
    verified_by uuid REFERENCES users (id) ON DELETE RESTRICT,

    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    -- A verification is all four columns or none of them. The half-filled shapes
    -- are each a specific bug: a `verified_at` with no method is a verification
    -- nobody can attribute, and a `verified_by` on a row that is not VERIFIED is
    -- what a replaced destination would look like if the service forgot to clear
    -- it.
    CONSTRAINT payout_destinations_verification_is_whole CHECK (
        (state = 'VERIFIED')
            = (verified_at IS NOT NULL AND verified_by IS NOT NULL AND verification_method IS NOT NULL)),

    -- And a reason belongs only to a refusal.
    CONSTRAINT payout_destinations_reason_only_when_rejected CHECK (
        (state = 'REJECTED') = (rejection_reason IS NOT NULL)
    )
);

-- "Which destinations are waiting on somebody", which is COMPLIANCE's queue and
-- the only list read of this table. Partial, because a verified destination is
-- read by its primary key and never scanned for.
CREATE INDEX payout_destinations_awaiting_idx
    ON payout_destinations (updated_at)
    WHERE state <> 'VERIFIED';

COMMENT ON TABLE payout_destinations IS
    'Where a creator is paid (#432). A provider token supplied by the creator and verified by COMPLIANCE, never a reference typed by whoever sends the money.';
COMMENT ON COLUMN payout_destinations.reference IS
    'The provider''s opaque token for the account, like stored_cards.token. Never an IBAN and never logged.';
COMMENT ON COLUMN payout_destinations.holder_name IS
    'What the bank says the account holder is called. Matched against creator_legal_subjects.legal_name; a disagreement is state NAME_MISMATCH.';
COMMENT ON COLUMN payout_destinations.verified_by IS
    'Somebody holding VERIFY_PAYOUT_DESTINATION, which V66 gave to COMPLIANCE and deliberately not to FINANCE.';
COMMENT ON COLUMN payout_destinations.verification_method IS
    'How ownership was proved. STAFF_ATTESTED today; PROVIDER_ACCOUNT_HOLDER and MICRO_TRANSFER wait on #422.';
