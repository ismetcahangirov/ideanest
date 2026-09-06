-- Who a creator legally is, and what a campaign was submitted under. Issue #430.
--
-- ---------------------------------------------------------------------------
-- Reverse:
--   DROP TABLE IF EXISTS campaign_legal_subjects;
--   DROP TABLE IF EXISTS creator_legal_subjects;
--
--   Order matters only in that the snapshot table is the one nothing else
--   references; neither points at the other, deliberately, and the header below
--   says why.
--
--   Lossy in one direction that is worth naming. `creator_legal_subjects` is
--   re-enterable -- a creator can type their VOEN in again -- and
--   `campaign_legal_subjects` is not: it records what was true at a moment that
--   has passed, and a campaign submitted last March cannot be resubmitted to
--   recover it. Export the snapshot table before reversing.
--
--   Safe under rolling deployment. Both tables are new, nothing reads them
--   except the code that ships with them, and a release running without that
--   code freezes no subjects -- which leaves campaigns unfrozen rather than
--   frozen wrongly, and #424's third point is that a campaign is decided under
--   the rule in force when it was submitted.
-- ---------------------------------------------------------------------------
--
-- ---------------------------------------------------------------------------
-- WHY THIS IS NOT `identity_verifications.subject_kind`
-- ---------------------------------------------------------------------------
--
-- V58 already has a `subject_kind` column, and #430 exists because that column
-- is the wrong place to read it from:
--
--   > that table is a record of a check, not a fact about the account.
--
-- The distinction has three consequences and each of them is a bug avoided.
-- The payout needs the subject to apply withholding, and a creator who has
-- never been asked to verify has no verification row at all -- so a payout
-- reading V58 would find nothing and would have to guess. The agreement needs
-- the subject to bind the right party, and it is bound at submission, which is
-- months before anybody looks at a document. And the tax posting needs it after
-- the fact, when the verification row may have expired and been re-decided.
--
-- So: one row per account, written by the creator, and the check against it
-- lives in V58 where it always did.
--
-- ---------------------------------------------------------------------------
-- VOEN IS SHAPE-CONSTRAINED AND NOTHING MORE
-- ---------------------------------------------------------------------------
--
-- Ten digits. The constraint below says exactly that and says it in the
-- schema, so that a row which reached the table by any route has the shape.
--
-- What it deliberately does not do is imply the number is real. #430:
--
--   > Writing a "validator" that appears to confirm existence would be worse
--   > than none: it would produce a green tick that means nothing, in front of
--   > the exact field where a green tick is relied upon.
--
-- Whether the number names a company, and whether this person may act for it,
-- is answered by a human reading a `COMPANY_REGISTRATION` document in V58's
-- queue. There is no column here for the outcome of that reading, because the
-- outcome is a verification and verifications live in V58.
--
-- ---------------------------------------------------------------------------
-- THE FOREIGN KEY CASCADES, AND THIS IS NOT A PREFERENCE
-- ---------------------------------------------------------------------------
--
-- The test suites truncate `users`. A reference to that table with ON DELETE NO
-- ACTION breaks around twenty tests in suites that have nothing to do with this
-- feature, and the failure surfaces three frames away from the cause. Every
-- other table pointing at `users` cascades for the same reason, and 17.4's
-- erasure wants the same thing: a legal subject is a fact about a relationship
-- that has ended.
--
-- `campaign_legal_subjects.user_id` cascades too, and that one costs something
-- worth stating: erasing a creator erases what their campaigns were submitted
-- under. The alternative is a snapshot that outlives the person it names, which
-- is exactly what 17.4 forbids. `audit_logs` keeps the fact that a subject was
-- recorded, without the subject.

CREATE TABLE creator_legal_subjects (
    -- The account, and the primary key. One creator is one legal subject at a
    -- time: an individual who registers a company edits this row. A second row
    -- would mean the platform had to choose between them, and there is no
    -- column that could say which to choose.
    user_id uuid PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,

    -- V58's vocabulary, unchanged and now shared -- the enum moved to
    -- `az.ideanest.shared.compliance` so that a module needing to name it need
    -- not reach into the verification module. Two vocabularies for one idea is
    -- one too many.
    subject_kind text NOT NULL
        CONSTRAINT creator_legal_subjects_kind_known CHECK (subject_kind IN ('INDIVIDUAL', 'LEGAL_ENTITY')),

    -- The name that must agree with #429's certificate subject and #432's
    -- account holder. Present for both kinds: a company has a registered name
    -- and a person has the name on their identity document, and the whole of
    -- the three-way match is against this column.
    legal_name text NOT NULL
        CONSTRAINT creator_legal_subjects_name_present CHECK (length(btrim(legal_name)) BETWEEN 1 AND 200),

    -- Ten digits, no separators. Stored normalised because a value that varies
    -- in punctuation cannot be compared, and #432 will compare it.
    tax_id text
        CONSTRAINT creator_legal_subjects_tax_id_shape CHECK (tax_id IS NULL OR tax_id ~ '^[0-9]{10}$'),

    registered_address text
        CONSTRAINT creator_legal_subjects_address_length CHECK (
            registered_address IS NULL OR length(btrim(registered_address)) BETWEEN 1 AND 500),

    registration_number text
        CONSTRAINT creator_legal_subjects_registration_length CHECK (
            registration_number IS NULL OR length(btrim(registration_number)) BETWEEN 1 AND 100),

    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    -- An individual has none of the entity fields, and a row that carried a
    -- VOEN against a person would be read as a company by anything that
    -- switched on the presence of the field rather than on the kind.
    CONSTRAINT creator_legal_subjects_individual_is_bare CHECK (
        subject_kind <> 'INDIVIDUAL'
            OR (tax_id IS NULL AND registered_address IS NULL AND registration_number IS NULL))
);

-- Note what is NOT here: a constraint requiring a legal entity to carry all
-- three of its fields.
--
-- It would be the natural companion to the one above and it is left out
-- deliberately. #424 has not set the threshold, so a creator may legitimately
-- be part-way through entering a company -- kind chosen, VOEN typed, address
-- not yet -- and a schema that refused that would force the application to
-- either buffer a half-filled form somewhere else or refuse to save until the
-- last field lands, which is how a form loses somebody's work. Completeness is
-- a gate's question, asked at submission by `LegalSubject.isComplete()`, and
-- it is asked of a row rather than enforced on one.

COMMENT ON TABLE creator_legal_subjects IS
    'Who a creator legally is (#430). A fact about the account, entered by the creator; whether it is true is V58''s verification.';
COMMENT ON COLUMN creator_legal_subjects.tax_id IS
    'VOEN, ten digits, shape-validated only. Whether it names a real company is answered by a human reading a registration extract.';
COMMENT ON COLUMN creator_legal_subjects.legal_name IS
    'Matched against a SIMA certificate subject (#429) and a payout destination holder (#432). A mismatch is MISMATCHED_NAME.';

CREATE TABLE campaign_legal_subjects (
    -- The campaign, and the primary key. A resubmission overwrites: that
    -- submission is the one being decided, and "what has this campaign been
    -- submitted under over its life" is a question nobody has asked.
    project_id uuid PRIMARY KEY REFERENCES projects (id) ON DELETE CASCADE,

    -- Whose subject was frozen. Denormalised from the campaign for
    -- `payouts.creator_id`'s reason: a campaign's creator is a mutable fact and
    -- this row was written for the person who held it at the time.
    user_id uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,

    subject_kind text NOT NULL
        CONSTRAINT campaign_legal_subjects_kind_known CHECK (subject_kind IN ('INDIVIDUAL', 'LEGAL_ENTITY')),

    legal_name text NOT NULL
        CONSTRAINT campaign_legal_subjects_name_present CHECK (length(btrim(legal_name)) BETWEEN 1 AND 200),

    tax_id text
        CONSTRAINT campaign_legal_subjects_tax_id_shape CHECK (tax_id IS NULL OR tax_id ~ '^[0-9]{10}$'),

    registered_address text,
    registration_number text,

    -- When the freeze happened, which is the submission. Distinct from the
    -- campaign's `submitted_at` only when a resubmission changed the subject,
    -- and that is precisely the case somebody will one day need to see.
    frozen_at timestamptz NOT NULL DEFAULT now()
);

-- "What has this creator submitted under", which is the console's read and the
-- one a dispute starts from.
CREATE INDEX campaign_legal_subjects_by_user_idx ON campaign_legal_subjects (user_id, frozen_at DESC);

-- There is deliberately no foreign key from here to `creator_legal_subjects`.
-- A snapshot that pointed at the live row would follow it when the creator
-- edited, which is the one thing this table exists to prevent, and a foreign
-- key would additionally stop a creator from ever clearing their subject while
-- an old campaign referenced it. The columns are copies and copies are the
-- point.

COMMENT ON TABLE campaign_legal_subjects IS
    'The creator''s legal subject as it stood when this campaign was submitted (#430). A copy, never a reference.';
