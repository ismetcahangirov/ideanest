-- §4.11's AD-15 and §12.3 (#315): the third verb. Preview and test send arrived
-- with #86; this is "edit".
--
-- ---------------------------------------------------------------------------
-- Reverse:
--   DROP TABLE IF EXISTS email_template_versions;
--
--   Safe in the sense that every template falls back to the translation
--   catalogue shipped in the jar, which is what the platform sent before this
--   migration -- so mail keeps working and simply reverts to the built-in copy.
--   Lossy in that every edit anybody has made is gone, including the history of
--   what was sent when. Export before reversing if any override is live.
-- ---------------------------------------------------------------------------
--
-- ---------------------------------------------------------------------------
-- WHY AN OVERRIDE TABLE AND NOT A TEMPLATE TABLE
-- ---------------------------------------------------------------------------
--
-- The obvious design moves every template out of the message catalogue and into
-- the database. It is wrong here for a reason that is easy to miss: the
-- catalogue is translated, reviewed and shipped with the code that reads it, so
-- a template in the database would have to carry every locale, and a deployment
-- that added a notification type would ship code that renders a template nobody
-- had inserted yet. The first missing row is a notification that cannot be sent.
--
-- So the catalogue stays authoritative and this table holds *overrides*: a row
-- exists only for a template somebody has edited, and its absence means "send
-- what the code says". Adding a notification type needs no row. Reversing this
-- migration needs no fallback plan, because the fallback is what is already
-- there.
--
-- ---------------------------------------------------------------------------
-- WHY VERSIONS RATHER THAN A ROW PER TEMPLATE
-- ---------------------------------------------------------------------------
--
-- §12.3's templates include the payment-failure notice, and #315 named the open
-- question about it: who may rewrite one. Part of that answer is that a rewrite
-- has to be reviewable after the fact -- "what did the notice say in March" is
-- asked when somebody claims they were never told their card had failed, and a
-- table that holds only the current text cannot answer it.
--
-- So each edit appends a version, the newest is the live one, and nothing is
-- updated in place. `email_deliveries` (V30) records what was sent; this records
-- what it was sent from.
--
-- ---------------------------------------------------------------------------
-- THE DECISION #315 WAS BLOCKED ON, AND WHAT IS ENFORCED HERE
-- ---------------------------------------------------------------------------
--
-- "No decision on who may rewrite a payment-failure notice." Two halves, and the
-- schema carries the half that is not a role question.
--
--   * **Who.** CONFIGURE_PLATFORM (V48), which only ADMINISTRATOR holds. That
--     is a policy and it lives in `StaffRole`, not here.
--
--   * **What may not be removed.** A payment-failure notice that no longer says
--     the card was declined, or no longer carries the link to fix it, is worse
--     than no override at all -- and no role check catches that, because the
--     administrator editing it is exactly who is allowed to. So a template
--     declares the placeholders it must keep, and `required_placeholders` is
--     checked against the body on write. The catalogue's own version is the
--     source of that list; it is copied onto the row so that a version stays
--     checkable after the code's list has moved on.
--
-- That is deliberately a narrow answer to a narrow question, and it does not
-- settle the larger one -- whether a transactional notice should be editable at
-- all -- which #315 can carry into a follow-up now that there is a screen to
-- argue about.
-- ---------------------------------------------------------------------------

CREATE TABLE email_template_versions (
    id uuid PRIMARY KEY,

    -- The NotificationType this overrides, as its enum name. Text with no CHECK
    -- listing the values, unlike most closed sets here: the set is large, grows
    -- with every notification the platform learns to send, and a constraint
    -- would make adding one a migration. The writing side is an enum in Java,
    -- which is what stops a spelling being invented.
    template_key text NOT NULL
        CONSTRAINT email_template_versions_key_shape CHECK (template_key ~ '^[A-Z][A-Z0-9_]{1,63}$'),

    -- §21.1's locale. An override is per language, because that is what a
    -- translation is; editing the English does not silently blank the Azerbaijani.
    locale text NOT NULL
        CONSTRAINT email_template_versions_locale_shape CHECK (locale ~ '^[a-z]{2}(-[A-Z]{2})?$'),

    -- Monotonic per (template_key, locale), starting at 1.
    version integer NOT NULL
        CONSTRAINT email_template_versions_version_positive CHECK (version >= 1),

    subject text NOT NULL
        CONSTRAINT email_template_versions_subject_present CHECK (length(btrim(subject)) BETWEEN 1 AND 300),

    body text NOT NULL
        CONSTRAINT email_template_versions_body_present CHECK (length(btrim(body)) BETWEEN 1 AND 50000),

    -- The placeholders the body must contain, copied from the catalogue at the
    -- moment of the edit. See the header on why this is on the row.
    required_placeholders text[] NOT NULL DEFAULT '{}'
        CONSTRAINT email_template_versions_placeholders_bounded CHECK (
            cardinality(required_placeholders) <= 40),

    -- Whether this version is the one that renders. Exactly one per
    -- (template_key, locale) may be live; withdrawing an override means marking
    -- them all not live, which sends the catalogue's copy again.
    live boolean NOT NULL DEFAULT true,

    note text
        CONSTRAINT email_template_versions_note_length CHECK (note IS NULL OR length(note) <= 2000),

    created_at timestamptz NOT NULL DEFAULT now(),

    created_by uuid NOT NULL REFERENCES users (id) ON DELETE RESTRICT,

    CONSTRAINT email_template_versions_unique_version UNIQUE (template_key, locale, version)
);

-- At most one live version per template and locale. Partial, for the reason
-- `fee_schedules_one_open_per_scope` is: the rule is only about the live ones,
-- and a template accumulates as many withdrawn versions as it has had edits.
CREATE UNIQUE INDEX email_template_versions_one_live
    ON email_template_versions (template_key, locale)
    WHERE live;

-- The render path: "is there an override for this template in this locale". One
-- lookup, and it is the index above -- no second one is needed.
--
-- The history read is "every version of this template", newest first.
CREATE INDEX email_template_versions_history
    ON email_template_versions (template_key, locale, version DESC);

COMMENT ON TABLE email_template_versions IS
    'Edited email copy, versioned, overriding the shipped catalogue (#315, AD-15, §12.3).';
