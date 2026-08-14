-- The link between a person here and the account they sign in with at Google
-- or Apple. One row per (person, provider).
--
-- Reverse:
--   DROP TABLE IF EXISTS provider_identities;

CREATE TABLE provider_identities (
    id                    uuid        PRIMARY KEY,
    user_id               uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    provider              text        NOT NULL,

    -- The provider's `sub` claim, and the only thing this table matches on.
    --
    -- Not the email. Google and Apple both let a person change the address on
    -- their account, and Apple hands out a per-app relay address that the user
    -- can switch off. An identity keyed on a mutable field means whoever holds
    -- the address next inherits the account it used to point at -- which is an
    -- account takeover performed entirely with legitimate credentials. `sub` is
    -- issuer-scoped, immutable, and never reassigned, which is what makes it
    -- the account and the email merely a fact about it.
    subject               text        NOT NULL,

    -- What the provider asserted about the address at the last sign-in, kept for
    -- support and for the audit trail. Nothing authenticates against it: the
    -- column below records whether the provider had actually proven it, and an
    -- unproven address is not permitted to reach an account at all.
    email                 citext,
    email_verified        boolean     NOT NULL DEFAULT false,

    -- Apple's Hide My Email. The address forwards today and stops the moment the
    -- user revokes it, so it is a poor address to reach a backer on about a
    -- pledge -- and worth knowing about before that matters (#25 follow-up).
    is_private_email      boolean     NOT NULL DEFAULT false,

    linked_at             timestamptz NOT NULL DEFAULT now(),
    last_authenticated_at timestamptz,
    created_at            timestamptz NOT NULL DEFAULT now(),
    updated_at            timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT provider_identities_provider_known CHECK (provider IN ('GOOGLE', 'APPLE')),
    CONSTRAINT provider_identities_subject_length CHECK (length(btrim(subject)) BETWEEN 1 AND 255),
    CONSTRAINT provider_identities_email_shape CHECK (
        email IS NULL OR email ~ '^[^@[:space:]]+@[^@[:space:]]+\.[^@[:space:]]+$'
    ),
    -- An address the provider has not proven must not be recorded as though it
    -- had been; the sign-in path refuses one outright, and this is the check
    -- that a support query or a bulk import cannot forget.
    CONSTRAINT provider_identities_verified_email_exists CHECK (
        NOT email_verified OR email IS NOT NULL
    )
);

-- One provider account belongs to one person. Without this, two rows could
-- claim the same Google subject and a sign-in would resolve to either.
CREATE UNIQUE INDEX provider_identities_provider_subject_key
    ON provider_identities (provider, subject);

-- And one person has at most one account per provider. A second Google identity
-- on the same user would make "sign out of Google everywhere" ambiguous, and
-- makes the link an unbounded set nobody audits. This index also serves the
-- lookup by user_id, which is a prefix of it.
CREATE UNIQUE INDEX provider_identities_user_provider_key
    ON provider_identities (user_id, provider);

CREATE TRIGGER provider_identities_set_updated_at
    BEFORE UPDATE ON provider_identities
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON TABLE provider_identities IS
    'Google and Apple sign-in links. Matched on the provider subject, never on the email.';

COMMENT ON COLUMN provider_identities.subject IS
    'The provider''s immutable `sub`. Matching on the email instead is an account takeover: providers let people change it.';
