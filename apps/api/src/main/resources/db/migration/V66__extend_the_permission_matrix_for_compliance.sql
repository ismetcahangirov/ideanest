-- The permission matrix grows a compliance half, and overrides become a record.
-- Issue #436.
--
-- ---------------------------------------------------------------------------
-- Reverse:
--   DROP TABLE IF EXISTS compliance_overrides;
--
--   ALTER TABLE identity_verifications
--       DROP CONSTRAINT IF EXISTS identity_verifications_reviewer_is_not_the_subject;
--
--   DELETE FROM staff_role_grants WHERE role = 'COMPLIANCE';
--   ALTER TABLE staff_role_grants DROP CONSTRAINT staff_role_grants_known;
--   ALTER TABLE staff_role_grants ADD CONSTRAINT staff_role_grants_known CHECK (
--       role IN ('MODERATOR', 'CURATOR', 'FINANCE', 'ADMINISTRATOR'));
--
--   The DELETE is not optional and it is not safe to skip: the narrowed
--   constraint is not satisfiable while a COMPLIANCE grant exists, so a reversal
--   that leaves the rows fails at the ALTER and leaves the table half-changed.
--   What it costs is that every person who held COMPLIANCE holds nothing, which
--   is the fail-closed direction and is the one to fail in.
--
--   Lossy in one direction worth naming. `compliance_overrides` is the record
--   that somebody was let past a rule, and §22.1's answer to "who authorised
--   this" is read out of it. The audit row survives -- `compliance.override_*`
--   does not cascade -- so what is lost is the expiry, the scope and the
--   visibility on the account, not the fact. Export before reversing.
--
--   Safe under rolling deployment in the expand direction: every statement here
--   adds, and the one CHECK added to `identity_verifications` is satisfied by
--   every row a previous release could have written -- see below on why that is
--   true rather than hoped.
-- ---------------------------------------------------------------------------
--
-- ---------------------------------------------------------------------------
-- Contract: none, and the `DROP CONSTRAINT` below is not one -- V47's argument,
-- unchanged.
--
-- Nothing is removed. No table, no column, and no value any row currently holds:
-- the constraint on `staff_role_grants.role` is widened from four names to five,
-- and every row that satisfied the old one satisfies the new one. A CHECK
-- constraint cannot be widened in place, which is why the statement is written
-- as a drop and an add; the pair is the smallest way to express "accept one more
-- spelling". Two statements in one migration are one transaction, so no request
-- ever sees the table unconstrained.
--
-- No later release has to finish this change, and no earlier release breaks
-- under it. A deployment still running the previous code writes only the four
-- names it knows about, which the wider constraint accepts.
-- ---------------------------------------------------------------------------
--
-- ---------------------------------------------------------------------------
-- WHAT THIS IS FOR
-- ---------------------------------------------------------------------------
--
-- Epic #421 introduced seven privileged actions that §3.1's matrix had no row
-- for and `staff_roles` (V48) had no capability for: publishing a version of a
-- legal document, reading an account's signed creator agreement, reading its
-- acceptance record, approving or rejecting an identity verification, opening
-- an identity document, verifying a payout destination, and overriding a
-- compliance requirement.
--
-- A capability that does not exist is either a thing nobody can do or -- worse
-- -- a thing that falls out of some broader role by accident. Publishing the
-- creator agreement was the second kind: it shipped under CONFIGURE_PLATFORM,
-- the same authority that changes a fee schedule, and V65 said in as many words
-- that #436 would narrow it.
--
-- The capabilities themselves are in `StaffCapability`, and which role confers
-- which is in `StaffRole`; V48's header has the argument for why that is code
-- and not data. What is here is the two halves that have to be in the database:
-- the role vocabulary, and the constraints that hold whatever the application
-- believes.
--
-- ---------------------------------------------------------------------------
-- WHY A FIFTH ROLE
-- ---------------------------------------------------------------------------
--
-- Because opening an identity document must be narrower than moderation and
-- narrower than administration, and there was nowhere to put it.
--
-- V58 encrypts identity documents in the application, keeps them for days
-- rather than for the life of the account, and audits every opening. That whole
-- design assumes a small number of people. If the capability falls out of
-- MODERATOR it is held by everybody who reviews a reported comment, and the
-- retention sweep is protecting a photograph of somebody's passport from
-- nobody in particular.
--
-- COMPLIANCE is that small number of people. It is the role that decides
-- identity, legal subject and payout destination -- the three questions this
-- epic added that are about who somebody is rather than about what they
-- posted.
--
-- **It does not carry MODERATE_CONTENT and it does not carry VIEW_FINANCE.**
-- A compliance reviewer approves a verification; they do not reject campaigns
-- and they do not read the ledger. Roles are additive, so somebody who does
-- both jobs holds both roles and that is visible on the staff screen -- which
-- is the outcome a union gives and a widened MODERATOR would have hidden.
--
-- ---------------------------------------------------------------------------
-- WHY VERIFYING A DESTINATION IS NOT FINANCE'S
-- ---------------------------------------------------------------------------
--
-- FINANCE already holds the authority to initiate a payout. The same role
-- deciding both *where* money goes and *that the destination is correct* is one
-- person holding both halves, which is the arrangement §4.11's dual approval
-- exists to prevent -- and the argument `StaffRole.FINANCE` already makes about
-- APPROVE_PAYOUT, applied one step earlier in the same sequence.
--
-- So VERIFY_PAYOUT_DESTINATION is COMPLIANCE's, and the decision is recorded
-- here rather than left implicit: the person who confirms that an IBAN belongs
-- to the creator it is filed under is not the person who sends money to it.

ALTER TABLE staff_role_grants
    DROP CONSTRAINT staff_role_grants_known;

ALTER TABLE staff_role_grants
    ADD CONSTRAINT staff_role_grants_known CHECK (
        role IN ('MODERATOR', 'CURATOR', 'FINANCE', 'COMPLIANCE', 'ADMINISTRATOR'));

COMMENT ON COLUMN staff_role_grants.role IS
    'One of StaffRole''s five. COMPLIANCE (#436) is the identity, legal-subject and payout-destination role; it is narrower than MODERATOR by construction.';

-- ---------------------------------------------------------------------------
-- NOBODY DECIDES ANYTHING ABOUT THEIR OWN ACCOUNT
-- ---------------------------------------------------------------------------
--
-- A member of staff may also be a creator. Nothing stops that and nothing
-- should: the people who run a crowdfunding platform are the people most likely
-- to run a campaign on it. What must stop is one of them approving their own
-- identity verification.
--
-- Stated as a constraint and not as a service check, for V21's reason. A rule
-- only the application knows is a rule that holds until somebody writes an
-- UPDATE by hand during an incident, and this is precisely the rule somebody
-- would be tempted to write an UPDATE around: it is in the way exactly when a
-- verification is urgent and the only reviewer available is the subject.
--
-- **On the null that this CHECK tolerates.** `payout_approvals` argues that a
-- two-column CHECK "silently passes when the second is null", which is why dual
-- approval is rows. That argument holds and this is the case it does not cover:
-- `reviewed_by` is null before anybody has reviewed, which is not an approval
-- and is not a thing to refuse. What the constraint has to refuse is a row that
-- names a reviewer equal to the subject, and it refuses every one of them.
--
-- The remaining gap is a reviewer erased afterwards by V58's ON DELETE SET
-- NULL, which turns an attributed decision into an unattributed one. That is
-- V58's deliberate trade -- a member of staff leaving must not delete the
-- record that a creator was checked -- and `audit_logs` keeps the attribution.
-- It cannot be used to launder a self-approval, because the row was refused at
-- the moment it was written.
--
-- **Why this needs no backfill.** Every existing row was written by
-- `IdentityVerifications`, which has never had a path that sets `reviewed_by`
-- to anything but the authenticated reviewer, and the console has never offered
-- a reviewer their own queue entry. NOT VALID would be the cautious form; it is
-- not used because a constraint that is never validated is a constraint that
-- silently stops applying to the rows it was added for, and the table is small
-- enough that the scan is nothing.
ALTER TABLE identity_verifications
    ADD CONSTRAINT identity_verifications_reviewer_is_not_the_subject CHECK (
        reviewed_by IS NULL OR reviewed_by <> user_id);

-- ---------------------------------------------------------------------------
-- AN OVERRIDE IS A ROW, AND THE ROW IS THE POINT
-- ---------------------------------------------------------------------------
--
-- An override exists because rules meet cases nobody anticipated. It is also
-- the mechanism by which every control this epic adds can be bypassed by one
-- person in one click.
--
-- So it is not a boolean on `users` and it is not a null in a gate. It is a
-- row that expires, carries a reason from a closed set plus the words of
-- whoever granted it, names that person, and is read back on the account it was
-- applied to. An override that is invisible after the fact is indistinguishable
-- from a control that was never there.
--
-- **Time-boxed in the schema and not in a job.** `expires_at` is NOT NULL, so
-- there is no shape for a permanent one. A sweep that deleted stale rows would
-- make expiry depend on a job running; here a gate asks for an override that
-- has not expired, and an override nobody revoked stops working on its own.
-- The row stays, because §22.1's question is "who authorised this and when",
-- and a deleted row answers neither.
CREATE TABLE compliance_overrides (
    id uuid PRIMARY KEY,

    -- Whose requirement was waived. CASCADE for `document_acceptances`' reason
    -- and for the practical one memory keeps re-teaching: a new foreign key to
    -- `users` that does not cascade breaks suites that truncate the table and
    -- names this constraint from three modules away.
    --
    -- It is also right on the merits. An override is a statement about a live
    -- relationship between the platform and a person; §17.4's erasure ends that
    -- relationship, and what survives it is the audit row, which does not
    -- cascade and which records that an override was granted.
    subject_user_id uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,

    -- What was waived. Closed, because a gate names one of these values in code
    -- and free text here is an override nothing honours, granted by somebody
    -- who watched the screen say it worked.
    --
    -- IDENTITY_VERIFICATION -- #431's gate on an approved verification.
    -- LEGAL_SUBJECT         -- #430's individual-or-entity requirement.
    -- PAYOUT_DESTINATION    -- #432's verified destination.
    -- AGREEMENT_SIGNATURE   -- #429's SIMA signature on the creator agreement.
    requirement text NOT NULL
        CONSTRAINT compliance_overrides_requirement_known CHECK (requirement IN (
            'IDENTITY_VERIFICATION',
            'LEGAL_SUBJECT',
            'PAYOUT_DESTINATION',
            'AGREEMENT_SIGNATURE')),

    -- Why, from a closed set. The same argument V58 makes about a rejection
    -- reason, in the other direction: a reason that is read back by an auditor
    -- has to be one the platform has words for, and a free-text field is where
    -- somebody eventually writes "asked on the phone".
    reason text NOT NULL
        CONSTRAINT compliance_overrides_reason_known CHECK (reason IN (
            'DOCUMENT_UNAVAILABLE_ABROAD',
            'PROVIDER_OUTAGE',
            'VERIFIED_BY_OTHER_MEANS',
            'LEGAL_ADVICE',
            'PLATFORM_ERROR')),

    -- And the words, which are required rather than optional. A role grant's
    -- note is a message to the next administrator and is allowed to be empty;
    -- this is the sentence an auditor reads, and required-but-empty is what a
    -- required field with no reader becomes -- so this one has a reader.
    note text NOT NULL
        CONSTRAINT compliance_overrides_note_present CHECK (length(btrim(note)) BETWEEN 1 AND 2000),

    -- RESTRICT, where V58's reviewer is SET NULL, and the difference is the
    -- whole value of the row. A verification records that somebody was checked
    -- and keeps meaning that with the reviewer erased. An override records that
    -- *a named person other than the subject* authorised an exception; with the
    -- name gone it is an anonymous waiver, which is the thing this table exists
    -- to make impossible.
    --
    -- It is also what makes the constraint below total: a column that cannot
    -- become null is a column a CHECK can compare without the gap
    -- `payout_approvals` warns about.
    granted_by uuid NOT NULL REFERENCES users (id) ON DELETE RESTRICT,

    granted_at timestamptz NOT NULL DEFAULT now(),

    -- NOT NULL: see the header. There is no permanent override.
    expires_at timestamptz NOT NULL,

    CONSTRAINT compliance_overrides_expires_after_grant CHECK (expires_at > granted_at),

    -- Withdrawn before it expired. Nullable, and never deleted: an override
    -- that was revoked after somebody used it is a different fact from one that
    -- was never granted, and only the row can tell them apart.
    revoked_at timestamptz,

    revoked_by uuid REFERENCES users (id) ON DELETE SET NULL,

    -- A revoker implies a time. A time does not imply a revoker, because the
    -- revoker's account can be erased out from under it -- V58's asymmetry, for
    -- V58's reason.
    CONSTRAINT compliance_overrides_revocation_has_a_time CHECK (
        revoked_by IS NULL OR revoked_at IS NOT NULL),

    -- **Nobody overrides a rule for themselves.**
    --
    -- Total, unlike the one added to `identity_verifications` above, because
    -- both columns are NOT NULL and `granted_by` is RESTRICT -- so there is no
    -- null for this comparison to pass through, now or later.
    CONSTRAINT compliance_overrides_grantor_is_not_the_subject CHECK (
        granted_by <> subject_user_id)
);

-- The gate's question: "does this account have a live override of this
-- requirement". Partial on the live half so that the index is the answer rather
-- than a starting point, and so that years of expired rows cost it nothing.
--
-- Not unique. Two live overrides of the same requirement for the same person is
-- odd rather than wrong -- a second granted before the first expired, for a
-- second reason -- and a unique index would make the second grant a 500 on a
-- screen where the honest outcome is two rows an auditor can read.
CREATE INDEX compliance_overrides_live_idx
    ON compliance_overrides (subject_user_id, requirement, expires_at DESC)
    WHERE revoked_at IS NULL;

-- "What has ever been waived for this account", which is what the account
-- screen draws and what the next person to look at the file must see. Every
-- row, expired and revoked included; that is the point of it.
CREATE INDEX compliance_overrides_by_subject_idx
    ON compliance_overrides (subject_user_id, granted_at DESC);

COMMENT ON TABLE compliance_overrides IS
    'One exception to one compliance requirement for one account (#436). Time-boxed, reasoned, attributed, and never granted by the subject.';
COMMENT ON COLUMN compliance_overrides.expires_at IS
    'NOT NULL by design: there is no permanent override. A gate asks for one that has not expired rather than for one nobody revoked.';
COMMENT ON COLUMN compliance_overrides.granted_by IS
    'RESTRICT rather than SET NULL, unlike identity_verifications.reviewed_by: an override with no named grantor is an anonymous waiver.';
