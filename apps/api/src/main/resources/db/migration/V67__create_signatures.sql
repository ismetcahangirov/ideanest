-- What a citizen signed, and with whose certificate. Issue #428.
--
-- ---------------------------------------------------------------------------
-- Reverse:
--   ALTER TABLE document_acceptances
--       DROP CONSTRAINT IF EXISTS document_acceptances_signature_fk;
--   DROP TABLE IF EXISTS signatures;
--
--   Order matters -- the acceptance's foreign key points here.
--
--   Lossy, and this is the worst loss in the schema to take casually. A row here
--   is the platform's only record that a named citizen signed a named text with
--   the legal force of a handwritten signature. There is no provider-side copy
--   the platform may rely on: SIMA answers a session once, and what is kept is
--   what was written here in that transaction. Export before reversing, and note
--   that #429's acceptances survive the reversal pointing at nothing -- the
--   column is nullable and goes back to meaning "a tick", which is the fail-safe
--   direction and is still a downgrade of evidence somebody relied on.
--
--   Safe under rolling deployment in the expand direction. The foreign key is
--   added to a column V65 already created and left nullable precisely so that
--   the referent could arrive later, which is this migration.
-- ---------------------------------------------------------------------------
--
-- ---------------------------------------------------------------------------
-- A TICK IS AN ACCEPTANCE; THIS IS A SIGNATURE
-- ---------------------------------------------------------------------------
--
-- For most of what this platform asks somebody to agree to, a tick is
-- proportionate, and `document_acceptances` is where a tick lives. For the
-- creator agreement -- the document that moves delivery liability onto a named
-- person and lets the platform recover a chargeback from them -- it is not.
--
-- A SIMA Imza signature is made by a citizen whose identity the state has
-- already verified, and it carries the legal force of a handwritten one. That
-- is the difference this table exists to record, and #429 is where the
-- difference is spent: it makes `document_acceptances.signature_id` non-null
-- for the creator agreement.
--
-- **This migration builds the table and nothing consumes it yet.** #428 ships
-- the mechanism; #429 is the caller. A signature row written by nothing is
-- inert, which is the correct state for a table whose adapter may not be
-- pointed at production SIMA until #423 answers the personal-data question
-- below.
--
-- ---------------------------------------------------------------------------
-- WHAT IS STORED, AND WHAT IS DELIBERATELY NOT
-- ---------------------------------------------------------------------------
--
-- Stored: the signature, the certificate's subject, the signing time, and the
-- hash of what was signed. That set is chosen to answer exactly one question --
-- "did this person sign this text, and when" -- and to answer nothing else.
--
-- **Not stored: any copy of the citizen's certificate material**, and no
-- identity document. V58 is where an identity document lives, with V58's
-- encryption and V58's retention sweep, and this must not become a second
-- uncontrolled place where a person's identity sits. An adapter that returned a
-- certificate chain drops it; what is kept is the subject line, which is what a
-- dispute is answered with.
--
-- **The name and the FIN are personal data under §17.4, and they are here on
-- purpose.** Without them the row proves that *a* certificate signed the text
-- and not that *this creator* did, which is the whole point -- #429 matches them
-- against the account and #430 stores the subject. §17.4's minimisation is
-- satisfied by keeping the two fields that carry the answer rather than the
-- certificate that carries them plus everything else.
--
-- **#423's personal-data row governs how long they may be kept, and this
-- migration does not decide it.** There is deliberately no sweep here and no
-- retention column: V58 sweeps because a document's purpose ends when a reviewer
-- has looked at it, and a signature's purpose is to be readable years later by a
-- court, which is `document_acceptances`' position rather than V58's. If #423's
-- adviser sets a shorter life, it arrives as a migration and a sweep, and it
-- will find the columns it needs to null out named here rather than buried in a
-- certificate blob.
--
-- ---------------------------------------------------------------------------
-- WHY THE HASH AND NOT THE TEXT
-- ---------------------------------------------------------------------------
--
-- The text is in `legal_documents`, immutably, with its own content hash --
-- V65's header argues why the body is stored and why a published version cannot
-- be edited. Storing it again here would be a second copy that could disagree
-- with the first.
--
-- What is stored is the hash that was actually signed, which is not the same
-- statement as "the hash of the document this signature is filed against". They
-- should be equal, and the moment they are not is the moment somebody has to
-- know: `SignatureProvider.verify` takes the document hash from the caller and
-- compares, and a signature that verifies against the wrong hash is the case
-- #428's tests name explicitly.

CREATE TABLE signatures (
    id uuid PRIMARY KEY,

    -- Which national provider produced it. Closed, and named the same way
    -- `payment_transactions.provider` is: SIMA is one provider today and ASAN
    -- Imza is the obvious second, so the column exists from the start rather
    -- than being added on the day the second arrives and every historical row
    -- has to be assumed.
    provider text NOT NULL
        CONSTRAINT signatures_provider_known CHECK (provider IN ('SIMA_IMZA')),

    -- The provider's own identifier for the signing session, kept so that a
    -- resolve can be repeated. Unique per provider: a session resolves to one
    -- signature, and two rows claiming the same session would make "what became
    -- of that session" ambiguous in the one direction that matters.
    provider_session_id text NOT NULL
        CONSTRAINT signatures_session_present CHECK (length(btrim(provider_session_id)) BETWEEN 1 AND 200),

    -- Whose account this was signed under. CASCADE for `document_acceptances`'
    -- reason, on both halves of it: a foreign key to `users` that does not
    -- cascade breaks suites that truncate the table, and §17.4's erasure ends
    -- the relationship this row is evidence about. What survives an erasure is
    -- the audit entry, which does not cascade.
    --
    -- Note what this is not: it is not the identity of the signer. The signer is
    -- the certificate subject below, and #429's whole job is to check that the
    -- two agree.
    signer_user_id uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,

    -- SHA-256 of the bytes the provider signed, lower-case hex -- the same shape
    -- and the same constraint as `legal_documents.content_hash`, so that the two
    -- can be compared without either side normalising first.
    document_hash text NOT NULL
        CONSTRAINT signatures_document_hash_shape CHECK (document_hash ~ '^[0-9a-f]{64}$'),

    -- The signature itself, base64. Bounded generously: a detached CAdES
    -- signature is kilobytes, and an unbounded column is how a certificate chain
    -- ends up in here despite the header saying it must not.
    signature_value text NOT NULL
        CONSTRAINT signatures_value_present CHECK (length(btrim(signature_value)) BETWEEN 1 AND 65536),

    -- The certificate's subject line as the provider stated it, kept verbatim.
    -- The two fields below are parsed out of it by the adapter; this is what
    -- they were parsed from, so a later disagreement about the parse can be
    -- settled without the certificate.
    certificate_subject text NOT NULL
        CONSTRAINT signatures_certificate_subject_present CHECK (
            length(btrim(certificate_subject)) BETWEEN 1 AND 1000),

    -- The citizen's name, as the state issued it. §17.4 personal data; see the
    -- header on why it is kept and on what #423 may yet say about how long.
    subject_name text NOT NULL
        CONSTRAINT signatures_subject_name_present CHECK (length(btrim(subject_name)) BETWEEN 1 AND 200),

    -- The FIN: Azerbaijan's seven-character personal identification number.
    -- Shape-constrained rather than free text, because a column that accepts
    -- anything is a column #429 will one day match an empty string against.
    subject_fin text NOT NULL
        CONSTRAINT signatures_subject_fin_shape CHECK (subject_fin ~ '^[0-9A-Z]{7}$'),

    -- When the citizen signed, as the provider reported it. Distinct from
    -- `created_at`, which is when the platform learned: a session resolved on
    -- retry is signed once and recorded twice, and only the first of those is a
    -- fact about the person.
    signed_at timestamptz NOT NULL,

    created_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT signatures_one_per_session UNIQUE (provider, provider_session_id)
);

-- "What has this account signed", which is #429's read and the console's.
-- Descending because it is read newest first.
CREATE INDEX signatures_by_signer_idx ON signatures (signer_user_id, signed_at DESC);

COMMENT ON TABLE signatures IS
    'One SIMA Imza signature: what was signed, by which certificate subject, and when (#428). Never a copy of the certificate material.';
COMMENT ON COLUMN signatures.document_hash IS
    'SHA-256 of the bytes that were signed, lower-case hex. Compared against legal_documents.content_hash rather than assumed equal to it.';
COMMENT ON COLUMN signatures.subject_fin IS
    'Personal data under §17.4. Kept because without it the row proves a certificate signed and not that this creator did. #423 governs how long.';

-- ---------------------------------------------------------------------------
-- THE REFERENT V65 LEFT ROOM FOR
-- ---------------------------------------------------------------------------
--
-- V65 created `document_acceptances.signature_id` with no foreign key and said
-- why: "there is nothing to reference -- the same shape, and the same argument,
-- as `pledges.payment_method_id` in V17: the column exists now so that the row a
-- client produces does not change on the day the referent lands."
--
-- This is that day. The column stays nullable -- a tick is still an acceptance
-- for seven of §22.2's eight documents -- and #429 is what makes it non-null for
-- the creator agreement, which it does with a partial constraint rather than by
-- changing this column.
--
-- RESTRICT rather than CASCADE, unlike everything else pointing at a person's
-- rows. Deleting a signature out from under an acceptance would turn a signed
-- agreement into a ticked one silently, which is the single transition this
-- table exists to make impossible. Erasing the signer removes both rows
-- together, because both cascade from `users` independently.
ALTER TABLE document_acceptances
    ADD CONSTRAINT document_acceptances_signature_fk
    FOREIGN KEY (signature_id) REFERENCES signatures (id) ON DELETE RESTRICT;

COMMENT ON COLUMN document_acceptances.signature_id IS
    'The SIMA Imza signature behind this acceptance, or null for a tick (#428). #429 makes it required for the creator agreement.';
