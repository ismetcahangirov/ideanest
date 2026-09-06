-- What a citizen was asked to sign, before they answered. Issue #429.
--
-- ---------------------------------------------------------------------------
-- Reverse:
--   DROP TABLE IF EXISTS signature_sessions;
--
--   Cheap, and this is the one table in this area where that is true. A row
--   here is a question in flight; `signatures` holds the answers and is
--   untouched. Reversing loses the sessions nobody has answered yet, and the
--   creators holding them see an expired session and start again -- which is a
--   case the flow already has, because a phone in a pocket produces it daily.
--
--   Safe under rolling deployment: the table is new and only the code that
--   ships with it reads or writes it.
-- ---------------------------------------------------------------------------
--
-- ---------------------------------------------------------------------------
-- WHY A SESSION IS A ROW AND NOT A HANDLE THE CLIENT HOLDS
-- ---------------------------------------------------------------------------
--
-- SIMA answers a session once. The obvious implementation is therefore the
-- stateless one: return the provider's session identifier to the browser, let
-- it poll, and write a `signatures` row when the provider says SIGNED. It is
-- wrong in three ways, and each of them is quiet.
--
-- **A session identifier would become a bearer token for somebody else's
-- signature.** Whoever holds it can resolve it, and a resolve writes a row
-- naming an account as having signed. Binding the session to the account that
-- started it, here, is what makes `resolve` checkable.
--
-- **The hash would arrive from the client.** A resolve has to know what was
-- supposed to be signed, and taking that from the request is taking it from the
-- party with the reason to change it. It is written here at `begin`, from the
-- document, and read back at `resolve`.
--
-- **A cancellation would leave nothing.** #429 asks for a test that "a
-- cancelled signature leaves the draft untouched", and a flow that writes only
-- successes cannot distinguish a cancelled session from one that never
-- happened. The outcome is recorded here, once, and a session that has already
-- been resolved is not resolved again.
--
-- ---------------------------------------------------------------------------
-- WHAT IS NOT STORED
-- ---------------------------------------------------------------------------
--
-- Not the FIN and not the mobile number the citizen typed to start the session.
-- They are passed to the provider and dropped. 17.4's minimisation asks for the
-- data the purpose needs, and the purpose of this table -- knowing what a
-- session was for, and whether it has been answered -- needs neither. The FIN
-- that matters is the one on the certificate, and that arrives with the
-- signature and is stored in `signatures` where V67 argued for it.
--
-- Not the verification code either. It exists to be read off two screens at the
-- same moment and has no life after that moment.

CREATE TABLE signature_sessions (
    id uuid PRIMARY KEY,

    -- Which national provider was asked. Same closed set as `signatures`, and
    -- named separately rather than joined through the signature, because a
    -- session that was cancelled has no signature to join to.
    provider text NOT NULL
        CONSTRAINT signature_sessions_provider_known CHECK (provider IN ('SIMA_IMZA')),

    -- The provider's handle. Unique per provider for `signatures`' reason: a
    -- session resolves to one outcome, and two rows claiming the same session
    -- would make "what became of it" ambiguous.
    provider_session_id text NOT NULL
        CONSTRAINT signature_sessions_handle_present CHECK (
            length(btrim(provider_session_id)) BETWEEN 1 AND 200),

    -- Who started it. CASCADE, for every reason the neighbouring tables give.
    signer_user_id uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,

    -- What was to be signed, taken from the document at `begin` and compared at
    -- `resolve`. The same shape as `legal_documents.content_hash` and
    -- `signatures.document_hash`, so the three compare without normalising.
    document_hash text NOT NULL
        CONSTRAINT signature_sessions_hash_shape CHECK (document_hash ~ '^[0-9a-f]{64}$'),

    -- Which document, so that a resolve can refuse a signature over a version
    -- that is no longer in force. Nullable and without a foreign key on
    -- purpose: a session may be started over something that is not a legal
    -- document -- #432's payout mandate is the next candidate -- and a column
    -- that could only hold one kind of referent would be replaced the first
    -- time it had to hold another.
    document_id uuid,

    -- What the citizen was told they were signing, kept because a dispute about
    -- a signature is a dispute about what the person believed they were
    -- approving.
    purpose text NOT NULL
        CONSTRAINT signature_sessions_purpose_present CHECK (length(btrim(purpose)) BETWEEN 1 AND 500),

    -- SIGNED, PENDING, CANCELLED or EXPIRED. PENDING until the provider says
    -- otherwise; the other three are terminal and a terminal session is never
    -- re-resolved.
    outcome text NOT NULL DEFAULT 'PENDING'
        CONSTRAINT signature_sessions_outcome_known CHECK (
            outcome IN ('PENDING', 'SIGNED', 'CANCELLED', 'EXPIRED')),

    -- The signature this session produced, when it produced one. RESTRICT
    -- rather than CASCADE, for `document_acceptances.signature_id`'s reason:
    -- deleting a signature out from under the session that produced it would
    -- turn a signed session into one that merely says SIGNED.
    signature_id uuid REFERENCES signatures (id) ON DELETE RESTRICT,

    started_at timestamptz NOT NULL DEFAULT now(),

    -- The provider's own deadline. Read rather than swept: a session past this
    -- is expired whether or not anything has run, which is `ComplianceOverride`'s
    -- argument and V55's -- a state that depended on a job having run is a state
    -- that is wrong for as long as the job is broken.
    expires_at timestamptz NOT NULL,

    resolved_at timestamptz,

    CONSTRAINT signature_sessions_one_per_handle UNIQUE (provider, provider_session_id),

    -- A signed session has a signature and a resolution; an unsigned one has
    -- neither. The constraint is here rather than in the service because the
    -- pair is what every later reader trusts.
    CONSTRAINT signature_sessions_signed_has_a_signature CHECK (
        (outcome = 'SIGNED') = (signature_id IS NOT NULL)),
    CONSTRAINT signature_sessions_resolved_when_terminal CHECK (
        (outcome = 'PENDING') = (resolved_at IS NULL))
);

-- "What is this creator waiting on", which is the resolve's own lookup and the
-- screen's. Descending because the newest session is the one being answered.
CREATE INDEX signature_sessions_by_signer_idx ON signature_sessions (signer_user_id, started_at DESC);

COMMENT ON TABLE signature_sessions IS
    'A signing session in flight (#429): what was to be signed, by whom, and what became of it. The answer itself is in signatures.';
COMMENT ON COLUMN signature_sessions.document_hash IS
    'Taken from the document at begin and compared at resolve, so that the hash a signature covers never arrives from the client.';
