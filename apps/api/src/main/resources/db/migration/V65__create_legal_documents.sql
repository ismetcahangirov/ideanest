-- §22.2's eight documents, and the record that somebody accepted one. Issue #425.
--
-- ---------------------------------------------------------------------------
-- Reverse:
--   DROP TABLE IF EXISTS document_acceptances;
--   DROP TABLE IF EXISTS legal_documents;
--   DROP FUNCTION IF EXISTS legal_documents_refuse_change();
--
--   Order matters -- acceptances reference documents.
--
--   Lossy in a direction worth naming. `document_acceptances` is the platform's
--   only record that a named person agreed to a named version of a document, and
--   it is the record a dispute is answered with. There is no provider-side copy
--   and no second system holding it: unlike a payment, an acceptance happened
--   entirely here. Export both tables before reversing.
--
--   Reversing is otherwise safe for behaviour, and fails closed. #426's gate on
--   campaign submission reads through `AgreementEntitlement`, so a deployment
--   without these tables has a bean that answers "nothing is required" -- see the
--   note there on why the absent-table case opens the gate rather than closing
--   it, which is the opposite of V62's choice and is argued from the difference
--   between a paywall and a legal record.
-- ---------------------------------------------------------------------------
--
-- ---------------------------------------------------------------------------
-- WHAT THIS BUILDS
-- ---------------------------------------------------------------------------
--
-- §22.2 lists eight documents the platform is required to have. Before this
-- migration none of them existed as anything the platform could show, version,
-- or prove somebody accepted: there was no `agreement`, `terms` or `consent`
-- anywhere in the schema.
--
-- This is the machinery, and only the machinery. It seeds no text. The words are
-- #423's adviser's and #439 publishes them; a migration that invented the terms
-- of use would be this repository writing a legal position and a regulator
-- reading it back to us. What is seeded is nothing at all, which is why the
-- eight `kind` values are a constraint rather than eight empty rows.
--
-- ---------------------------------------------------------------------------
-- WHY TWO TABLES, AND WHY THE SPLIT IS THE WHOLE DESIGN
-- ---------------------------------------------------------------------------
--
-- A document is a thing the platform publishes; an acceptance is a thing a
-- person did. They have different authors, different lifetimes and different
-- retention rules, and one table would have to pick one of each.
--
-- The alternative that keeps suggesting itself -- a boolean on `users`, or three
-- of them -- fails on the first question anybody asks of it. A creator accepts
-- the creator agreement; a backer accepts the backer agreement; both accept the
-- terms of use; and each accepts a *version* of each. A flag cannot say which
-- document, and three flags cannot say which version.
--
-- ---------------------------------------------------------------------------
-- WHY THE BODY IS STORED AND NOT A URL
-- ---------------------------------------------------------------------------
--
-- A document at a URL is a document that changed. The acceptance record has to
-- be able to reproduce the text somebody agreed to, years later, without
-- depending on a file nobody versioned and a deployment nobody kept.
--
-- The hash is the second half of that. #429 has SİMA sign a hash, and the record
-- has to be able to prove *which text* was signed -- so the hash is stored
-- beside the body rather than computed on read, and a body that no longer
-- hashes to it is a body somebody edited.
--
-- ---------------------------------------------------------------------------
-- WHY PUBLISHING IS NOT AN EDIT
-- ---------------------------------------------------------------------------
--
-- A published version is immutable, enforced by a trigger and not by a service
-- check, for V21's reason: a rule that only the application knows is a rule that
-- holds until somebody writes an UPDATE by hand during an incident. An
-- acceptance names a version; a version whose text can be edited afterwards
-- makes every acceptance of it worthless, which is the one thing this whole
-- epic exists to prevent.
--
-- A correction is therefore a new version. That is more ceremony than editing a
-- typo deserves and exactly the right amount for the case the ceremony is for.
--
-- A draft -- `published_at IS NULL` -- is freely editable and is what the admin
-- screen writes into. At most one draft exists per (kind, locale), so "the
-- draft of the creator agreement in Azerbaijani" resolves to one row rather
-- than to whichever of four somebody opened last.
--
-- ---------------------------------------------------------------------------
-- WHY ACCEPTANCES ARE NEVER SWEPT, WHERE V58's DOCUMENTS ARE
-- ---------------------------------------------------------------------------
--
-- These two migrations sit beside each other and take opposite decisions about
-- personal data, so the difference is stated here rather than left for somebody
-- to discover as a contradiction.
--
-- §17.4's minimisation says to stop holding personal data as soon as it has
-- served its purpose. V58 follows that rule as aggressively as it can be
-- followed: an identity document is a photograph of somebody's passport, its
-- purpose ends the moment a reviewer has looked at it, and it is deleted within
-- days.
--
-- An acceptance is the opposite case. It is a reference and a timestamp -- who,
-- which version, when, from where -- and not a document about a person. Its
-- purpose is precisely to be readable years later, by a court or an auditor
-- asking what somebody agreed to. Deleting it on a schedule would destroy the
-- evidence that the platform behaved correctly, in the name of a rule that
-- exists to protect the person the evidence is about.
--
-- Neither table is wrong. They hold different things.
--
-- ---------------------------------------------------------------------------
-- WHY PUBLISHING IS AUDITED, AND WHICH PERMISSION IT NEEDS
-- ---------------------------------------------------------------------------
--
-- Publishing a version of the creator agreement changes what every creator who
-- submits after it is bound by. CLAUDE.md: every privileged action is audited,
-- and `LegalDocuments` writes `legal.document_published` on the way.
--
-- The capability is `CONFIGURE_PLATFORM` today, which only ADMINISTRATOR holds
-- -- the same authority that changes a fee schedule, and for the same reason:
-- one screen, changing what the running platform obliges everybody to. #436
-- gives this action its own row in §3.1's matrix and its own capability, and
-- narrowing it there is a change to one `requireCapability` call.

CREATE TABLE legal_documents (
    id uuid PRIMARY KEY,

    -- §22.2's eight, closed. Free text here is how a ninth document appears that
    -- nothing knows how to require -- and the two the platform gates on,
    -- CREATOR_AGREEMENT and BACKER_AGREEMENT, are named in code by #426 and
    -- #427. A typo in a free-text column would be a gate silently checking a
    -- document nobody publishes.
    kind text NOT NULL
        CONSTRAINT legal_documents_kind_known CHECK (kind IN (
            'TERMS_OF_USE',
            'PRIVACY_POLICY',
            'COOKIE_POLICY',
            'PLATFORM_RULES',
            'CREATOR_AGREEMENT',
            'BACKER_AGREEMENT',
            'DELIVERY_AND_REFUND_POLICY',
            'DISPUTE_RESOLUTION_POLICY')),

    -- §21.1's four, the same set V11 constrains its translations to. A document
    -- is a document per language: the Azerbaijani one is the one that governs,
    -- and the other three exist so that somebody can read what they are agreeing
    -- to. Which is why the locale is part of the version's identity rather than a
    -- translation hanging off one -- see the acceptance table on what that
    -- means for what a person accepted.
    locale text NOT NULL
        CONSTRAINT legal_documents_locale_known CHECK (locale IN ('az', 'en', 'ru', 'tr')),

    -- Monotonic per (kind, locale), from 1. Allocated by `LegalDocuments` as
    -- max + 1 inside the transaction that writes the draft, and made true by the
    -- unique constraint below rather than by that read.
    version integer NOT NULL
        CONSTRAINT legal_documents_version_is_positive CHECK (version >= 1),

    -- What the page is called, in the document's own language. Separate from the
    -- kind because "Creator Agreement" is a translation and CREATOR_AGREEMENT is
    -- an identifier, and a screen that drew the second one would be showing a
    -- reader a database value.
    title text NOT NULL
        CONSTRAINT legal_documents_title_present CHECK (length(btrim(title)) BETWEEN 1 AND 200),

    -- The text itself. See the header on why this is not a URL.
    --
    -- Bounded, generously. A megabyte is far more than any of the eight will ever
    -- be and far less than a paste accident, and an unbounded text column on a
    -- table an administrator types into is a way to put a video in a database.
    body text NOT NULL
        CONSTRAINT legal_documents_body_present CHECK (length(btrim(body)) BETWEEN 1 AND 1048576),

    -- SHA-256 of the body, lower-case hex. Written by the application, which is
    -- also what #429 will hand to SİMA -- so the two cannot disagree about which
    -- bytes were hashed. The shape is constrained here so that a row carrying
    -- something that is not a digest is refused rather than signed.
    content_hash text NOT NULL
        CONSTRAINT legal_documents_content_hash_shape CHECK (content_hash ~ '^[0-9a-f]{64}$'),

    -- When this version starts governing. Set at publication, and allowed to be
    -- in the future: a change to the creator agreement that everybody should be
    -- told about a fortnight before it bites is a publication now with an
    -- `effective_from` later, not a reminder in somebody's calendar.
    effective_from timestamptz,

    -- Null means draft, and draft is the only editable state. Everything below
    -- keys off this column.
    published_at timestamptz,

    -- ON DELETE SET NULL, following V58's `reviewed_by`: a member of staff
    -- leaving must not delete the terms of use. What is lost is the attribution,
    -- and `audit_logs` keeps that with a retention policy somebody argued for.
    published_by uuid REFERENCES users (id) ON DELETE SET NULL,

    -- A published version has a date it starts governing, and a version with a
    -- date it starts governing has been published. Stated as an equality because
    -- either half alone is a row a reader would misread: a draft with an
    -- `effective_from` looks live to a query that forgot to check, and a
    -- published version with none has no answer to "since when".
    CONSTRAINT legal_documents_published_has_a_start CHECK (
        (published_at IS NULL) = (effective_from IS NULL)),

    created_at timestamptz NOT NULL DEFAULT now(),

    created_by uuid REFERENCES users (id) ON DELETE SET NULL,

    updated_at timestamptz NOT NULL DEFAULT now(),

    -- The version is the point of the record. Two rows claiming to be version 3
    -- of the Azerbaijani creator agreement would make every acceptance naming
    -- version 3 ambiguous.
    CONSTRAINT legal_documents_version_unique UNIQUE (kind, locale, version)
);

CREATE TRIGGER legal_documents_set_updated_at
    BEFORE UPDATE ON legal_documents
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- **At most one draft per document per language.**
--
-- Partial, so it constrains drafts and says nothing about the published rows,
-- of which there are deliberately many. Without it, two administrators opening
-- the same document produce two drafts and one of them publishes work the other
-- cannot see they lost.
CREATE UNIQUE INDEX legal_documents_one_draft_per_kind_locale
    ON legal_documents (kind, locale)
    WHERE published_at IS NULL;

-- **The question every reader asks: which version governs now.**
--
-- Descending on both, so the answer is the first row: the latest version whose
-- `effective_from` has arrived. Partial on published, because a draft must never
-- be an answer to it and excluding drafts in the index is stronger than
-- remembering to exclude them in each query.
CREATE INDEX legal_documents_in_force
    ON legal_documents (kind, locale, effective_from DESC, version DESC)
    WHERE published_at IS NOT NULL;

COMMENT ON TABLE legal_documents IS
    'One version of one of §22.2''s eight documents, in one of §21.1''s four languages. Published versions are immutable.';

-- ---------------------------------------------------------------------------
-- THE REFUSAL
-- ---------------------------------------------------------------------------
--
-- V21's shape, for V21's reason and one more. A published version is the thing
-- an acceptance points at, and an acceptance of an editable document is not
-- evidence of anything -- so the immutability has to hold against a hand-written
-- UPDATE during an incident, which is exactly when somebody would write one.
--
-- Row-level and conditional, unlike `audit_logs_refuse_change`, because this
-- table has a mutable half: a draft is edited freely, and only publication
-- closes it. The trigger therefore refuses an UPDATE whose OLD row was
-- published, and lets the publication itself through -- that statement's OLD row
-- is the draft.
--
-- `restrict_violation` so the class-23 SQLSTATE lands where the driver and
-- Spring already map integrity failures, and whoever meets it meets the same
-- kind of error a check constraint gives.
CREATE FUNCTION legal_documents_refuse_change() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'legal_documents version % of %/% is published; % is refused',
        OLD.version, OLD.kind, OLD.locale, tg_op
        USING ERRCODE = 'restrict_violation',
              HINT = 'A published version is immutable. Correct it by publishing a new version.';
END;
$$;

COMMENT ON FUNCTION legal_documents_refuse_change() IS
    'Refuses any change to a published version. See V65 for why this is a trigger and not a service check.';

CREATE TRIGGER legal_documents_published_is_immutable
    BEFORE UPDATE OR DELETE ON legal_documents
    FOR EACH ROW
    WHEN (OLD.published_at IS NOT NULL)
    EXECUTE FUNCTION legal_documents_refuse_change();

-- ---------------------------------------------------------------------------
-- WHAT SOMEBODY AGREED TO
-- ---------------------------------------------------------------------------

CREATE TABLE document_acceptances (
    id uuid PRIMARY KEY,

    -- **ON DELETE CASCADE, and not by preference.** The test suites truncate
    -- `users`; a foreign key to that table with NO ACTION breaks roughly twenty
    -- tests in suites that have nothing to do with legal documents, and the
    -- failure names this constraint from three modules away.
    --
    -- It is also right on the merits, narrowly. An acceptance is evidence about
    -- a relationship between the platform and a person, and §17.4's erasure ends
    -- that relationship; what survives an erasure is the audit row, which does
    -- not cascade and which records that an acceptance was made. A closed
    -- account's own consent record is not a thing the platform has a purpose for.
    user_id uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,

    -- RESTRICT: a published version is never deleted -- the trigger above makes
    -- that true rather than merely intended -- and this is the constraint that
    -- would catch a draft being removed out from under an acceptance, which
    -- cannot happen and should still be impossible.
    document_id uuid NOT NULL REFERENCES legal_documents (id) ON DELETE RESTRICT,

    accepted_at timestamptz NOT NULL DEFAULT now(),

    -- `inet` and not text, for V2's and V21's reason: it validates the value and
    -- normalises IPv6, so the database is the authority on what an address is.
    -- Nullable, because an acceptance recorded by a job or a test has no client
    -- and a row that invented one would be worse than a row that says so.
    ip_address inet,

    -- What the client called itself, truncated by the application the way
    -- `audit_logs.user_agent` is. Evidence of the same weak kind: it corroborates
    -- and it proves nothing on its own.
    user_agent text
        CONSTRAINT document_acceptances_user_agent_length CHECK (
            user_agent IS NULL OR length(user_agent) <= 512),

    -- **Nullable until #429, and no foreign key yet.**
    --
    -- A tick is an acceptance; a SİMA İmza signature is an acceptance with the
    -- legal force of a handwritten one. #429 makes this non-null for the creator
    -- agreement, once #428 has built the table it will point at.
    --
    -- No foreign key today because there is nothing to reference -- the same
    -- shape, and the same argument, as `pledges.payment_method_id` in V17: the
    -- column exists now so that the row a client produces does not change on the
    -- day the referent lands.
    signature_id uuid,

    -- **One acceptance per person per version.**
    --
    -- Not per person per document: accepting version 4 is a separate fact from
    -- accepting version 3, and the whole point of the record is to say which. Not
    -- replaced either -- an acceptance is appended, and the history of what
    -- somebody agreed to over the years is the answer to the only question this
    -- table is ever asked.
    --
    -- A retry that re-sends the same confirmation therefore lands on this index
    -- rather than writing a second row, and `DocumentAcceptances.accept` turns
    -- the violation into the existing acceptance.
    CONSTRAINT document_acceptances_one_per_user_version UNIQUE (user_id, document_id)
);

-- "Has this account accepted the current version of the creator agreement?" --
-- #426's gate, asked once per campaign submission. Leads on the account because
-- that is what is always known.
CREATE INDEX document_acceptances_by_user
    ON document_acceptances (user_id, document_id);

-- "Who accepted this version, and when" -- the console's read of a version, and
-- the query a dispute starts from. Descending because it is read newest first.
CREATE INDEX document_acceptances_by_document
    ON document_acceptances (document_id, accepted_at DESC);

COMMENT ON TABLE document_acceptances IS
    'One row per (account, document version). Appended, never replaced, and never swept -- see V65 on why this differs from V58.';
