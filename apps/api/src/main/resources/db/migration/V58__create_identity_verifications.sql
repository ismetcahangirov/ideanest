-- §22.1's anti-money-laundering row, and §5.4's R6 control: identity
-- verification for creators. Issue #105.
--
-- ---------------------------------------------------------------------------
-- Reverse:
--   DROP TABLE IF EXISTS identity_documents;
--   DROP TABLE IF EXISTS identity_verifications;
--
--   Order matters. Dropping these destroys every document held and every
--   decision recorded, so a creator who has been verified would have to be
--   verified again. Nothing else references either table -- see the header on
--   why nothing is gated on the outcome yet -- so no campaign, pledge or payout
--   changes state.
-- ---------------------------------------------------------------------------
--
-- ---------------------------------------------------------------------------
-- WHAT THIS BUILDS AND WHAT IT DELIBERATELY DOES NOT
-- ---------------------------------------------------------------------------
--
-- #105 asks for "document capture with restricted access and a retention
-- limit", and that is the whole of what is here: somewhere to put a document,
-- rules about who may see it, and a sweep that deletes it.
--
-- IT DOES NOT DECIDE WHO MUST VERIFY. §22.1 lists "identity verification
-- thresholds for creators" as one of the questions requiring a specific legal
-- answer, at priority High, and #71 -- the legal opinion on holding third-party
-- funds -- carries `status: needs-decision`. A threshold invented here would be
-- a compliance position this repository made up, and it would be the position a
-- regulator reads back to us.
--
-- So verification is requestable and recordable, nothing is blocked on it, and
-- `ideanest.verification.required` exists so the day the answer arrives is a
-- configuration change and a wiring change rather than a migration.
--
-- ---------------------------------------------------------------------------
-- WHY THE DOCUMENT IS A SEPARATE TABLE FROM THE DECISION
-- ---------------------------------------------------------------------------
--
-- Because they have different lifetimes, and that difference is the whole
-- feature. The decision -- verified, when, by whom -- is a record the platform
-- needs to keep: it is the answer to "was this creator checked", asked by an
-- auditor years later. The document is a photograph of somebody's passport, and
-- §17.4's minimisation says to stop holding it as soon as it has been looked at.
--
-- One table would force one retention rule for both, and whichever was chosen
-- would be wrong: keeping the passport for the life of the account, or losing
-- the record that the account was ever checked.
--
-- ---------------------------------------------------------------------------
-- WHY THE BYTES ARE IN POSTGRES AND ENCRYPTED IN THE APPLICATION
-- ---------------------------------------------------------------------------
--
-- There is no object storage on this platform (§13.1: ingestion is not built).
-- The alternatives were to build it for this feature, or to hold a bounded
-- number of small, short-lived blobs in the database. The second is what is
-- here, and the bound is the point: a document is capped at a few megabytes, at
-- most a handful per creator, and deleted within days of a decision. That is a
-- table that stays small by construction rather than by hope.
--
-- The encryption is the application's, exactly as V36 argues for shipping
-- addresses: disk encryption does not answer a backup, a read replica, a
-- `SELECT *` in a support console, or an SQL injection, and `pgcrypto` takes the
-- passphrase as a query argument -- which lands in `pg_stat_statements` and in
-- any statement log an operator switches on during an incident.
--
-- ---------------------------------------------------------------------------
-- WHY THERE IS NO `deleted` FLAG ON A DOCUMENT
-- ---------------------------------------------------------------------------
--
-- A retention limit that marks a row instead of removing it is not a retention
-- limit. The sweep deletes; what survives is `identity_verifications`, which
-- records that a document of a given kind was seen and when it was destroyed.

CREATE TABLE identity_verifications (
    id uuid PRIMARY KEY,

    -- One row per creator, replaced rather than accumulated: this is a current
    -- state. The history of decisions lives in `audit_logs`, which is the table
    -- with a retention policy somebody has argued for.
    user_id uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,

    -- REQUESTED:  the platform asked; nothing submitted yet.
    -- SUBMITTED:  documents are held and nobody has looked.
    -- APPROVED:   a member of staff was satisfied.
    -- REJECTED:   a member of staff was not. Resubmittable.
    -- EXPIRED:    approved, and the approval has aged past the configured life.
    state text NOT NULL DEFAULT 'REQUESTED'
        CONSTRAINT identity_verifications_state_known CHECK (state IN (
            'REQUESTED', 'SUBMITTED', 'APPROVED', 'REJECTED', 'EXPIRED')),

    -- INDIVIDUAL or LEGAL_ENTITY -- §4.2's "individual or legal entity". What is
    -- asked for differs: a person shows an identity document, a company shows a
    -- registration extract.
    subject_kind text NOT NULL DEFAULT 'INDIVIDUAL'
        CONSTRAINT identity_verifications_subject_known CHECK (subject_kind IN ('INDIVIDUAL', 'LEGAL_ENTITY')),

    -- Who decided, and when. SET NULL on the reviewer: a member of staff leaving
    -- must not delete the record that a creator was checked, which is the half an
    -- auditor asks about. What is lost is the attribution, and `audit_logs` is
    -- where that is kept with a retention policy somebody argued for.
    reviewed_by uuid REFERENCES users (id) ON DELETE SET NULL,

    reviewed_at timestamptz,

    -- A reviewer implies a time. A time does NOT imply a reviewer, because the
    -- reviewer's account can be erased out from under it.
    CONSTRAINT identity_verifications_review_has_a_time CHECK (
        reviewed_by IS NULL OR reviewed_at IS NOT NULL),

    CONSTRAINT identity_verifications_decided_has_reviewer CHECK (
        state NOT IN ('APPROVED', 'REJECTED') OR reviewed_at IS NOT NULL),

    -- Why it was refused, from a closed set rather than free text. A reason a
    -- creator is shown has to be one the product has written words for, and a
    -- free-text field is where somebody eventually pastes what they saw on the
    -- document.
    rejection_reason text
        CONSTRAINT identity_verifications_rejection_reason_known CHECK (rejection_reason IS NULL OR rejection_reason IN (
            'UNREADABLE', 'EXPIRED_DOCUMENT', 'MISMATCHED_NAME', 'INCOMPLETE', 'SUSPECTED_FORGERY')),

    CONSTRAINT identity_verifications_rejection_matches_state CHECK (
        (state = 'REJECTED') = (rejection_reason IS NOT NULL)),

    -- When an approval stops counting. Set on approval from the configured life;
    -- null otherwise. A column rather than a computed age, because the life can
    -- change and a verification has to keep the rule it was approved under.
    expires_at timestamptz,

    CONSTRAINT identity_verifications_expiry_needs_approval CHECK (
        expires_at IS NULL OR state IN ('APPROVED', 'EXPIRED')),

    -- When the documents behind this decision were destroyed. NULL means either
    -- none were held or they are still held; `identity_documents` is the
    -- authority on which. Recorded here because it outlives them.
    documents_erased_at timestamptz,

    created_at timestamptz NOT NULL DEFAULT now(),

    updated_at timestamptz NOT NULL DEFAULT now(),

    -- One current verification per person. See the header.
    CONSTRAINT identity_verifications_one_per_user UNIQUE (user_id)
);

-- The staff queue: submitted, oldest first, which is the order a queue is
-- worked. A partial index because everything else outnumbers it within a month.
CREATE INDEX identity_verifications_queue_idx
    ON identity_verifications (created_at)
    WHERE state = 'SUBMITTED';

-- The expiry sweep, and the retention sweep's first half.
CREATE INDEX identity_verifications_expiry_idx
    ON identity_verifications (expires_at)
    WHERE state = 'APPROVED' AND expires_at IS NOT NULL;

CREATE TABLE identity_documents (
    id uuid PRIMARY KEY,

    verification_id uuid NOT NULL REFERENCES identity_verifications (id) ON DELETE CASCADE,

    -- What was submitted. Recorded outside the ciphertext because the queue
    -- shows it and because the retention record has to be able to say what was
    -- destroyed without decrypting anything.
    kind text NOT NULL
        CONSTRAINT identity_documents_kind_known CHECK (kind IN (
            'ID_CARD_FRONT', 'ID_CARD_BACK', 'PASSPORT', 'RESIDENCE_PERMIT', 'COMPANY_REGISTRATION')),

    -- The verified media type, from the magic bytes and never from what the
    -- client called it. §17.3's file upload row.
    content_type text NOT NULL
        CONSTRAINT identity_documents_content_type_known CHECK (content_type IN (
            'image/jpeg', 'image/png', 'application/pdf')),

    -- Bytes before encryption, for the queue to show a size without opening it.
    byte_length integer NOT NULL
        CONSTRAINT identity_documents_length_is_positive CHECK (byte_length > 0),

    -- AES-256-GCM, the same envelope V36 uses for a shipping address and for the
    -- same reasons. The nonce is per row and never reused; the key label says
    -- which key sealed it, so a rotation can add a key before it moves the
    -- primary.
    ciphertext bytea NOT NULL,

    nonce bytea NOT NULL
        CONSTRAINT identity_documents_nonce_is_twelve_bytes CHECK (length(nonce) = 12),

    key_id text NOT NULL
        CONSTRAINT identity_documents_key_id_shape CHECK (key_id ~ '^[a-z0-9._-]{1,64}$'),

    uploaded_at timestamptz NOT NULL DEFAULT now()
);

-- Every document of one verification: what the review screen loads, and what
-- the retention sweep deletes.
CREATE INDEX identity_documents_verification_idx ON identity_documents (verification_id);

-- The retention sweep's second half: documents older than the limit, whatever
-- their verification decided. See the header on why there is no flag.
CREATE INDEX identity_documents_uploaded_idx ON identity_documents (uploaded_at);
