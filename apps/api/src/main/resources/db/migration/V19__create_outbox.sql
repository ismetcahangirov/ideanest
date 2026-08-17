-- §8.3's transactional outbox (#135): a commit and its published event cannot
-- diverge.
--
-- ---------------------------------------------------------------------------
-- WHAT THIS TABLE IS FOR
-- ---------------------------------------------------------------------------
--
-- Everything the platform announces today is published from a listener that runs
-- after the transaction committed — `AuthEvents`, `ProjectEvents`,
-- `LaunchReminderDelivery`, and every comment in those files says the same thing:
-- there is a window between the commit and the send, and a crash inside it loses
-- the message. The alternative is worse. Publishing *before* the commit sends a
-- message about something that then rolled back: a verification link for an
-- account that does not exist, a "your pledge is confirmed" for a pledge nobody
-- has.
--
-- Both failures come from the same cause. **Two systems cannot be committed
-- to atomically** — a database and a mail transport, a database and a broker —
-- and no ordering of the two calls makes them one. So the intent to publish is
-- written to the database *as part of the business transaction*, and something
-- else publishes it afterwards. The commit that creates the pledge is the commit
-- that creates the event; there is no instant at which one exists and the other
-- does not, and there is nothing to lose in a window because there is no window.
--
-- What this buys, precisely:
--
--   * **A rolled back business write has no event.** The row went with it.
--   * **A committed business write has its event, for ever, until it is
--     published.** A crash between the commit and the dispatch costs latency and
--     nothing else: the row is still PENDING when the process comes back.
--   * **At-least-once, never at-most-once.** The relay dispatches, then commits
--     the fact that it dispatched. A crash between those two republishes, which
--     is why every handler has to tolerate redelivery — see `id` below.
--
-- ---------------------------------------------------------------------------
-- WHAT IT IS NOT
-- ---------------------------------------------------------------------------
--
-- **Not a job queue.** #134 is that, and the two are easy to confuse because both
-- are rows a scheduled sweep picks up. The difference is who owns the payload: a
-- job is work the platform decided to do later and may reschedule, retry
-- differently, or cancel; an outbox row is a statement that something already
-- happened and cannot be untrue. Nothing here may be edited or cancelled, which
-- is why there is no `cancelled` state and no way back from PUBLISHED.
--
-- **Not a broker integration.** #135 is the guarantee, not the transport. The
-- dispatch target is an interface (`OutboxDispatcher`), and the only
-- implementation today republishes in process. When a broker arrives, it
-- implements that interface and this table does not change.
--
-- ---------------------------------------------------------------------------
-- Rolling deployment
-- ---------------------------------------------------------------------------
--
-- One new table, four new indexes, and nothing else touched. No column is
-- dropped, no constraint is added to an existing table, and no previous release
-- reads or writes anything here — the older build simply does not know the table
-- exists, and the newer one is the only writer. Both halves of a rolling deploy
-- are safe in either order. This is an EXPAND with no contract half.
--
-- Reverse:
--   DROP TABLE IF EXISTS outbox_events;
--
--   Safe while nothing enqueues, which is true of every release before this one.
--   Afterwards it costs exactly one thing, and it is worth naming rather than
--   calling it a tidy-up: **every PENDING row is an event that had been promised
--   and is then never delivered.** Those are not recoverable from anywhere else,
--   because the point of the table is that it is the only record of the intent —
--   the business row it was written with says what is true now, not what anybody
--   was owed a notification about. So reversing this is a decision to drop
--   whatever had not yet been published, and the way to make it cheaply is to
--   stop writing (deploy the previous build), let the relay drain to zero PENDING
--   rows, and only then drop the table.

CREATE TABLE outbox_events (
    -- **The identifier a consumer deduplicates on.**
    --
    -- Delivery is at-least-once by construction, so a handler will see the same
    -- event twice: a relay that dispatched and then failed to commit dispatches
    -- again, and it must, because the alternative is deciding an event was
    -- delivered on the strength of a call whose outcome nobody recorded. This
    -- column is what makes that survivable — it is stable across every redelivery
    -- of the same event, and it is the row's own primary key rather than something
    -- minted per attempt, so a consumer that remembers the identifiers it has
    -- processed is idempotent without any cooperation from here.
    --
    -- It is deliberately *not* `shared/idempotency`'s machinery. That table
    -- answers a different question — "what did we answer this account when it
    -- last sent this key" — and its rows carry an `account_id` that references
    -- `users`, an HTTP status, a response body, and §17.2's 24-hour retention.
    -- None of that describes a consumer's "have I handled event X", and reusing
    -- it would mean an event handler inventing an account to own the key.
    id              uuid        PRIMARY KEY,

    -- **The order events go out in, decided by the database.**
    --
    -- Ordering matters within an aggregate and nowhere else: a consumer that saw
    -- `pledge.collected` before the `pledge.confirmed` it followed would build a
    -- state that never existed, whereas two different pledges have no order to
    -- get wrong. The relay therefore walks this column and refuses to dispatch an
    -- event while an earlier PENDING one for the same aggregate exists.
    --
    -- A counter rather than `occurred_at` or the v7 identifier, because both of
    -- those are millisecond-resolution and **the case that matters is a tie**:
    -- two events for one aggregate written by one transaction share a
    -- millisecond, and the tail of a v7 UUID is random. A sequence cannot tie.
    --
    -- GENERATED ALWAYS, so a caller cannot choose its own place in the queue.
    --
    -- Honest limit, because it is the sort of thing that reads as a guarantee and
    -- is not one: the number is taken when the row is *inserted*, not when its
    -- transaction commits, so two concurrent writers can commit out of sequence
    -- order and the relay will then publish them in commit order rather than in
    -- sequence order. For one aggregate that is not reachable in practice — two
    -- writes to the same aggregate serialise on the aggregate's own row, so the
    -- second transaction cannot have taken its number before the first committed.
    -- Across aggregates it is reachable and harmless, because there is no order to
    -- violate.
    sequence_no     bigint      GENERATED ALWAYS AS IDENTITY,

    -- **Which thing this happened to**, as a type and an identifier rather than a
    -- foreign key. There is deliberately no reference: the column names rows in
    -- `pledges`, `projects`, `users` and whatever comes next, and no single
    -- foreign key can point at four tables. A reference would also be the wrong
    -- shape even if it could — an event is a statement about the past, and it
    -- stays true after the aggregate it describes is deleted. A cascade here
    -- would quietly discard the very notification that says something was
    -- removed.
    aggregate_type  text        NOT NULL,
    aggregate_id    uuid        NOT NULL,

    -- What happened, in the vocabulary the consumers switch on: `pledge.confirmed`,
    -- `project.launched`. Not an enum type, because the set grows with every
    -- feature and a new event must not be a migration and a deployment ordering
    -- problem; the consumers ignore what they do not recognise.
    event_type      text        NOT NULL,

    -- **The serialised event, as text and not jsonb**, for V18's reason about
    -- `idempotency_keys.response_body`. jsonb is a parsed representation: it
    -- discards key order and whitespace and re-serialises on the way out, so the
    -- bytes a consumer receives would not be the bytes the transaction committed.
    -- The payload is also never queried into — the relay hands it over verbatim
    -- and nothing here reads inside it — so the one thing jsonb would buy is not
    -- something anybody spends.
    payload         text        NOT NULL,

    -- PENDING -> PUBLISHED, or PENDING -> DEAD. There is no way back from either,
    -- and no fourth state: a state the relay does not know about is an event it
    -- silently never looks at again, which is a lost message with no symptom.
    state           text        NOT NULL DEFAULT 'PENDING',

    -- **Deliveries attempted, not deliveries failed.** Bounded retries need a
    -- number that only ever goes up, and this is it; `next_attempt_at` is where
    -- the backoff derived from it is written down.
    attempts        integer     NOT NULL DEFAULT 0,

    -- Why the last attempt failed, for whoever has to look at a dead letter. Kept
    -- on a retryable row too, because "it has failed six times, all with the same
    -- timeout" is the sentence that distinguishes a transport outage from a
    -- payload nobody can parse.
    last_error      text,

    -- When the event happened, which is when its transaction wrote it. Assigned by
    -- the database, like every other `created_at` in this schema, because it
    -- records a fact about the row rather than driving a rule.
    occurred_at     timestamptz NOT NULL DEFAULT now(),

    -- **When the relay may next try.** Written by the application from the
    -- injected Clock and never defaulted here, for V17's and V18's reason: the
    -- backoff is a rule, a rule needs one home, and a test has to be able to ask
    -- what happens ten minutes later without waiting ten minutes.
    --
    -- Equal to `occurred_at` on a new row, so an event is eligible the moment it
    -- is visible.
    next_attempt_at timestamptz NOT NULL,

    -- When it went out. Null until it has.
    published_at    timestamptz,

    CONSTRAINT outbox_events_state_known CHECK (state IN ('PENDING', 'PUBLISHED', 'DEAD')),
    CONSTRAINT outbox_events_aggregate_type_length CHECK (
        length(btrim(aggregate_type)) BETWEEN 1 AND 64
    ),
    CONSTRAINT outbox_events_event_type_length CHECK (
        length(btrim(event_type)) BETWEEN 1 AND 128
    ),
    -- A dispatcher handed an empty body publishes a message that says nothing, and
    -- the consumer cannot tell that from a serialisation bug on the way in.
    CONSTRAINT outbox_events_payload_is_present CHECK (length(btrim(payload)) >= 1),
    CONSTRAINT outbox_events_attempts_is_not_negative CHECK (attempts >= 0),
    -- Both directions. A PUBLISHED row with no instant cannot be audited — "was
    -- this sent before the incident" has no answer — and a PENDING row carrying
    -- one claims to have been sent and is about to be sent again.
    CONSTRAINT outbox_events_published_when_it_says_so CHECK (
        (state = 'PUBLISHED') = (published_at IS NOT NULL)
    ),
    -- The whole value of a terminal state is that somebody can find out what
    -- happened to the event. Abandoned for a reason nobody wrote down, after a
    -- number of attempts nobody counted, is not a dead letter — it is a
    -- disappearance.
    CONSTRAINT outbox_events_dead_letters_say_why CHECK (
        state <> 'DEAD' OR (last_error IS NOT NULL AND attempts >= 1)
    )
);

-- Identity columns are not unique by declaration, and this one carries the
-- ordering guarantee. Unique so that two rows cannot claim one place in the
-- queue, and an index because the relay's claim reads the queue in exactly this
-- order.
CREATE UNIQUE INDEX outbox_events_sequence_key ON outbox_events (sequence_no);

-- **The relay's own index**, partial over the only state it looks at. The queue
-- is short by design — the relay drains it — so an ordered walk of the pending
-- rows filtering on `next_attempt_at` is the right plan, and it stays right as
-- the table grows, because everything already published has left this index.
CREATE INDEX outbox_events_queue_idx ON outbox_events (sequence_no) WHERE state = 'PENDING';

-- The ordering check: "is there an earlier pending event for this aggregate".
-- Asked once per candidate row, so without this index the relay's claim degrades
-- into a scan of the pending set per event it dispatches.
CREATE INDEX outbox_events_aggregate_queue_idx
    ON outbox_events (aggregate_type, aggregate_id, sequence_no) WHERE state = 'PENDING';

-- "What has stopped moving", asked by an operator and by whatever alerting
-- §18.3 grows. Partial over a set that should be empty, which is what makes the
-- question cheap to ask often against a table that will be large.
CREATE INDEX outbox_events_dead_idx ON outbox_events (occurred_at) WHERE state = 'DEAD';

COMMENT ON TABLE outbox_events IS
    '§8.3''s transactional outbox (#135): the event is written by the same transaction as the business change, and a relay publishes it afterwards. At-least-once; handlers deduplicate on id.';
COMMENT ON COLUMN outbox_events.id IS
    'The stable event identifier. The same across every redelivery, so a consumer that remembers it is idempotent.';
COMMENT ON COLUMN outbox_events.sequence_no IS
    'Dispatch order. The relay will not publish an event while an earlier PENDING one for the same aggregate exists.';
COMMENT ON COLUMN outbox_events.payload IS
    'The serialised event, verbatim. text and not jsonb, so a consumer receives the bytes the transaction committed.';
COMMENT ON COLUMN outbox_events.state IS
    'PENDING -> PUBLISHED, or PENDING -> DEAD once the attempts are exhausted. Neither is reversible.';
