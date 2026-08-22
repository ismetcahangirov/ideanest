-- §9.3's R-07 and §17.2's webhook row (#66): what a payment provider told us,
-- once.
--
-- ---------------------------------------------------------------------------
-- Reverse:
--   DROP TABLE IF EXISTS provider_webhook_events;
--
--   Costs the deduplication. Every row here is a provider event the platform has
--   already acted on, and the unique index over `(provider, provider_event_id)`
--   is the only thing standing between a redelivery and a second refund. So this
--   is safe while no provider is configured -- which is every release up to and
--   including this one, because #60 has not been answered -- and afterwards it
--   is safe only with the webhook endpoint returning 503, long enough for the
--   providers' own retry windows to elapse. Dropping it under live traffic means
--   accepting that anything the provider re-sends will be processed again.
-- ---------------------------------------------------------------------------
--
-- ---------------------------------------------------------------------------
-- WHY THE ROW IS THE CLAIM, AND WHY THERE IS NO PENDING STATE
-- ---------------------------------------------------------------------------
--
-- Every asynchronous integration on this platform so far -- `outbox_events`,
-- `notifications`, `email_deliveries` -- has a state that moves: something is
-- recorded, something else picks it up, and the two are separated so that a crash
-- in between costs latency rather than the message. **This table is deliberately
-- the other shape**, and the difference is which side owns the retry.
--
-- The outbox retries because *we* are the sender and nobody else will. A webhook
-- is the opposite: the provider is the sender, every provider in §9.3 retries a
-- delivery it did not get a 2xx for, and R-07 is on the list precisely so that
-- this is true of whichever one is chosen. So the honest arrangement is that a
-- delivery is handled inside the request that carried it, in one transaction with
-- the row that records it:
--
--   * **It committed** -- the row is here, the effect happened, and a redelivery
--     hits the unique index and is answered 200 without doing anything twice.
--   * **It did not** -- there is no row and no effect, the response is a 500, and
--     the provider sends it again. Nothing is half-done, because "the effect" and
--     "we have seen this event" are one commit.
--
-- A `PENDING` state would break exactly that. A row committed before its handler
-- ran would make the next redelivery look like a duplicate of work that never
-- happened, which is the one failure mode a deduplication table must not have.
-- The cost is that a slow handler is a slow HTTP response, and it is a cost worth
-- naming: handlers here do the smallest durable thing and record an outbox event
-- for the rest, so the work that is genuinely slow happens on §8.3's relay where
-- it belongs.
--
-- ---------------------------------------------------------------------------
-- WHAT MAKES A REPLAY A REPLAY
-- ---------------------------------------------------------------------------
--
-- §17.2 asks for three things and they answer three different attacks, so all
-- three are here rather than one standing in for the others.
--
--   * **The signature** is what makes the body ours to trust at all. Verified by
--     the provider's adapter -- `PaymentProvider.parseWebhook` -- before anything
--     is written, so an unsigned or wrongly signed delivery never reaches this
--     table. There is deliberately **no `signature_verified` column**: a `false`
--     in it would describe a row that should not exist, and a column that is
--     always `true` is a column somebody will eventually write `false` into.
--   * **The timestamp** is what stops a *validly signed* body being replayed
--     later. `provider_signed_at` is the instant the provider says it signed, and
--     the ingestion refuses anything outside
--     `ideanest.payment.webhooks.tolerance`. It is stored rather than merely
--     checked because "how far behind were the deliveries during the incident" is
--     otherwise unanswerable.
--   * **The identifier** is what stops the same event being *acted on* twice, and
--     it is the only one of the three that survives a restart. Signature and
--     timestamp both pass on a genuine redelivery; the unique index is what makes
--     that redelivery harmless.
--
-- The source allowlist is the third control in §17.2 and is not a column: it is a
-- property of the deployment's network, and an address recorded here would be the
-- address of whatever proxy terminated the connection.
--
-- ---------------------------------------------------------------------------
-- Rolling deployment
-- ---------------------------------------------------------------------------
--
-- One new table, one unique index, one ordinary index, and nothing else touched.
-- No previous release reads or writes it, and no previous release exposes the
-- endpoint that writes it. Both halves of a rolling deploy are safe in either
-- order. This is an EXPAND with no contract half.

CREATE TABLE provider_webhook_events (
    id                 uuid        PRIMARY KEY,

    -- Which adapter verified and parsed this, from `ProviderName`. Half of the
    -- deduplication key, because §9.3 requires at least two providers and two
    -- providers' event identifiers share no namespace -- a bare
    -- `provider_event_id` unique index would eventually refuse a genuine event
    -- from one provider because the other had used the same string.
    provider           text        NOT NULL,

    -- **The provider's own identifier for the event**, which is what a redelivery
    -- repeats. Not our identifier and not a hash of the body: a provider that
    -- re-sends an event with a refreshed timestamp changes the bytes and not the
    -- event, so a content hash would let the redelivery through.
    provider_event_id  text        NOT NULL,

    -- What the provider says happened, normalised by the adapter into the
    -- platform's own vocabulary rather than kept in the provider's. Text and not
    -- an enum for `outbox_events.event_type`'s reason: the set grows with every
    -- provider integrated, and an unrecognised type is a row this table still has
    -- to be able to hold -- see `IGNORED` below.
    event_type         text        NOT NULL,

    -- **The body as it arrived**, verbatim, and `text` rather than `jsonb` for
    -- V18's and V19's reason -- with one that is sharper here than anywhere else
    -- in the schema. This is the evidence in a dispute: the bytes that were
    -- signed. `jsonb` discards key order and whitespace and re-serialises, so a
    -- signature could no longer be re-verified against what is stored, which is
    -- the one thing anybody would ever want this column for.
    payload            text        NOT NULL,

    -- When the provider says it signed the delivery. See the header: stored so
    -- that delivery lag is answerable, checked before insert so that a replay is
    -- refused rather than recorded.
    provider_signed_at timestamptz,

    -- When it reached us, and when it was dealt with. Two columns and not one,
    -- because the gap between them is this endpoint's latency and the header
    -- explains why that is a number somebody will want.
    --
    -- `received_at` is the database's, like every other `created_at` here.
    -- `handled_at` is the application's, from the injected `Clock`, because it is
    -- part of what the handler decided rather than a fact about the row.
    received_at        timestamptz NOT NULL DEFAULT now(),
    handled_at         timestamptz NOT NULL,

    -- `PROCESSED` or `IGNORED`, and no third value. There is no `PENDING` -- see
    -- the header -- and no `FAILED`, because a handler that fails takes its
    -- transaction with it and leaves no row at all.
    --
    -- **`IGNORED` is not an error.** It is an event the platform verified,
    -- recorded, and had nothing to do with: a provider sends every event type it
    -- has, and answering 200 while doing nothing is the correct handling of the
    -- ones we have not asked for. Recording them is what makes "the provider says
    -- it sent us the dispute notification" checkable.
    state              text        NOT NULL,

    -- What the handler did, in one line, for the support conversation. Null on an
    -- `IGNORED` row, which needs no explanation beyond its state.
    outcome            text,

    CONSTRAINT provider_webhook_events_state_known CHECK (state IN ('PROCESSED', 'IGNORED')),
    CONSTRAINT provider_webhook_events_provider_length CHECK (
        length(btrim(provider)) BETWEEN 1 AND 32
    ),
    CONSTRAINT provider_webhook_events_event_id_length CHECK (
        length(btrim(provider_event_id)) BETWEEN 1 AND 255
    ),
    CONSTRAINT provider_webhook_events_event_type_length CHECK (
        length(btrim(event_type)) BETWEEN 1 AND 128
    ),
    -- An empty body is a delivery that says nothing, and it cannot be told apart
    -- from a body that was lost between the socket and the insert.
    CONSTRAINT provider_webhook_events_payload_is_present CHECK (length(btrim(payload)) >= 1),
    CONSTRAINT provider_webhook_events_outcome_length CHECK (
        outcome IS NULL OR length(btrim(outcome)) BETWEEN 1 AND 500
    )
);

-- **Exactly once, as an index.** The whole of the guarantee: a redelivery's
-- insert is refused, the ingestion catches the refusal, and the response is a 200
-- with nothing done. A service-level "have I seen this" read would lose the race
-- between two deliveries arriving at once, which is exactly what a provider
-- retrying an event it thinks timed out produces.
CREATE UNIQUE INDEX provider_webhook_events_identity_key
    ON provider_webhook_events (provider, provider_event_id);

-- "What has this provider sent us, and when", which is the read an incident
-- starts from. Descending, because it always starts at the most recent delivery.
CREATE INDEX provider_webhook_events_received_idx
    ON provider_webhook_events (provider, received_at DESC);

COMMENT ON TABLE provider_webhook_events IS
    '§9.3''s R-07 and §17.2 (#66): one row per verified provider delivery, written in the same transaction as the effect it caused. The unique index is the exactly-once guarantee.';
COMMENT ON COLUMN provider_webhook_events.payload IS
    'The body as it arrived, verbatim. text and not jsonb so that the bytes that were signed can be re-verified.';
COMMENT ON COLUMN provider_webhook_events.state IS
    'PROCESSED or IGNORED. There is no PENDING: the row and the effect are one commit. There is no FAILED: a failed handler leaves no row.';
