-- §4.10's notification table and §12.2's delivery diagram (#85): one domain event,
-- fanned out to one row per (recipient, channel), subject to what that person asked
-- for.
--
-- ---------------------------------------------------------------------------
-- TWO TABLES, BECAUSE THERE ARE TWO KINDS OF FACT
-- ---------------------------------------------------------------------------
--
-- §7.2 names them both -- `notifications` and `notification_preferences` -- and
-- they are genuinely different things:
--
--   * A **preference** is a standing instruction from a person. It is small, it
--     is read on every fan-out, and it outlives everything it affects.
--   * A **notification** is one attempted delivery. It is written by a consumer
--     of a domain event, it has a queue's lifecycle -- pending, sent, or dead --
--     and there is one per channel rather than one per event.
--
-- ---------------------------------------------------------------------------
-- PREFERENCES ARE DATA. DEFAULTS ARE POLICY, AND THEY ARE NOT ROWS
-- ---------------------------------------------------------------------------
--
-- **There is no seed here, and no `DEFAULT` that encodes a product decision.**
-- A user who has never opened the settings page has no rows in
-- `notification_preferences` at all, and that is the whole design:
--
--   * Seeding a row per (user, category, channel) at registration would be
--     seven categories times three channels for every account on the platform,
--     written before anybody has expressed a preference about any of them -- and
--     it would freeze today's default into every existing account, so changing
--     the default would become a backfill instead of a deployment.
--   * It would also make the two indistinguishable afterwards. "IMMEDIATE
--     because we chose it for them" and "IMMEDIATE because they chose it" are
--     different facts, and only the second one may survive a change of policy.
--
-- So the absence of a row *is* the answer "no preference expressed", and the
-- default lives in `notification.domain.DeliveryPolicy` where it can be argued
-- about, read, and changed in one place. A row appears the moment a person says
-- something, and never before.
--
-- The corollary is that this table can be truncated without losing correctness:
-- every account falls back to the policy. That is a property worth having.
--
-- ---------------------------------------------------------------------------
-- WHICH CHANNELS A TYPE HAS AT ALL IS ALSO POLICY, NOT DATA
-- ---------------------------------------------------------------------------
--
-- §4.10's table has ticks and crosses -- "Pledge edited" has no push, "24 hours
-- remaining" has no email. Those crosses are not "off by default": they are
-- channels the type does not have, and no preference can turn them on. They live
-- in `NotificationType` beside the categories, for the same reason the defaults
-- do. A row here naming a (category, channel) that no type in that category
-- supports is harmless -- it is an instruction about something that never
-- happens.
--
-- ---------------------------------------------------------------------------
-- REDELIVERY
-- ---------------------------------------------------------------------------
--
-- `OutboxMessage`'s contract is at-least-once, in those words: "handlers must
-- tolerate redelivery". `notifications_event_recipient_channel_key` is how this
-- consumer honours it -- the same shape `referral_attributions_pledge_key` uses
-- in V24. The listener checks first and the index decides, so two deliveries of
-- one `pledge.confirmed` produce one row per channel and the second attempt is
-- refused by the database rather than by a check that happened to run first.
--
-- The key is (event, recipient, channel) rather than (event) because the fan-out
-- is precisely over recipients and channels: one event legitimately becomes three
-- rows, and a key on the event alone would let the second and third through only
-- by not existing.
--
-- ---------------------------------------------------------------------------
-- THE OUTBOUND HALF, AND WHY IT IS A QUEUE AND NOT A CALL
-- ---------------------------------------------------------------------------
--
-- The inbound half is already solved: the fan-out runs inside the outbox
-- dispatch transaction, so a notification is written by the same transaction that
-- records the event as delivered. A domain change that rolled back produces
-- nothing, and a process that dies leaves the event PENDING and the fan-out is
-- redone.
--
-- The outbound half -- actually handing a message to a transport -- cannot be in
-- that transaction, because a sent email is not rolled back. So it is a second
-- queue, with the same columns and the same vocabulary as `outbox_events`:
-- `state`, `attempts`, `last_error`, `next_attempt_at`. An operator at three in
-- the morning should not have to learn two.
--
-- **This is at-least-once and cannot be made exactly-once.** The send happens
-- before the transaction that records it commits, so a crash in between sends
-- again -- exactly the trade `OutboxDispatch` argues for, in the same direction:
-- a duplicate is visible and can be collapsed, a loss is visible to nobody. What
-- makes the duplicate collapsible is that `notifications.id` is stable across
-- every attempt, so it is the idempotency key a real transport is handed. #86's
-- mail provider and #87's push service must use it.
--
-- ---------------------------------------------------------------------------
-- DIGEST, WHICH IS MODELLED HERE AND NOT COMPLETED HERE
-- ---------------------------------------------------------------------------
--
-- §4.10 ends with "Preferences are per category and per channel, with a digest
-- option", and §12.2 says a digest accumulates until a scheduled job combines it.
-- The preference vocabulary therefore has three values and not two, and a
-- notification whose resolved mode is DIGEST is written in state `HELD`: it
-- exists, it is not sent, and `notifications_held_idx` is the index the combining
-- job will claim it by.
--
-- **That job is not in this change and there is nothing that drains `HELD`.**
-- Said plainly rather than implied: a user who selects digest mode today
-- accumulates rows that are never combined and never sent. It is deliberate --
-- a digest has to be *rendered into one message and handed to a transport*, and
-- the transports are #86 and #87 -- but it is the largest thing #85 leaves
-- undone, and it is why `DeliveryPolicy` defaults nobody to it.
--
-- ---------------------------------------------------------------------------
-- Rolling deployment
-- ---------------------------------------------------------------------------
--
-- Two new tables, six new indexes, and nothing existing touched. No column is
-- dropped, no constraint is added to an existing table, and no previous release
-- reads or writes anything here. Both halves of a rolling deploy are safe in
-- either order, and the old release simply does not fan anything out. This is an
-- EXPAND with no contract half.
--
-- Reverse:
--   DROP TABLE IF EXISTS notifications;
--   DROP TABLE IF EXISTS notification_preferences;
--
--   In either order -- neither references the other. Safe while nothing writes
--   here, which is true of every release before this one. Afterwards it discards
--   every in-app inbox and every preference anybody set; the first is
--   unrecoverable (the events that produced it are already PUBLISHED and will not
--   be redelivered) and the second silently returns everybody to the defaults,
--   which is a change of behaviour nobody is told about.

-- ---------------------------------------------------------------------------
-- notification_preferences -- the standing instructions
-- ---------------------------------------------------------------------------
CREATE TABLE notification_preferences (
    -- Surrogate rather than the natural triple, like every other table here:
    -- UUID v7, minted by the application.
    id           uuid        PRIMARY KEY,

    -- Cascades. A preference is a setting on an account and nothing else refers
    -- to it, so there is nothing for a hard delete to orphan. §17.4 anonymises in
    -- place rather than deleting, so this fires only for a genuine removal.
    user_id      uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,

    -- **Per category, not per type.** §4.10's last line, and it is a usability
    -- decision rather than a storage one: twenty-two switches times three
    -- channels is a settings page nobody finishes reading, and the rows that
    -- belong together -- "payment collected", "payment failed", "final payment
    -- warning" -- are exactly the ones somebody wants to answer once.
    --
    -- A check constraint rather than an enum type, for V19's reason about
    -- `event_type`: the set grows with the product, and a new category should be
    -- a migration rather than a deployment-ordering problem.
    category     text        NOT NULL,

    channel      text        NOT NULL,

    -- OFF, IMMEDIATE, or DIGEST. One column rather than a boolean beside a
    -- digest flag, because the three are alternatives and a row that is both off
    -- and digesting is a state somebody would eventually have to interpret.
    delivery_mode text       NOT NULL,

    -- When this instruction was given and last changed. Not a rule -- nothing
    -- reads them -- so they are the database's `now()`, like every other
    -- `created_at` in this schema.
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),

    -- One instruction per person per category per channel. Two would mean the
    -- fan-out picking one, and it would pick whichever the index happened to
    -- return.
    CONSTRAINT notification_preferences_key UNIQUE (user_id, category, channel),
    CONSTRAINT notification_preferences_category_known CHECK (
        category IN ('PLEDGES', 'CAMPAIGN', 'PAYMENTS', 'COMMUNITY', 'REWARDS', 'DISCOVERY', 'SECURITY')
    ),
    CONSTRAINT notification_preferences_channel_known CHECK (
        channel IN ('IN_APP', 'EMAIL', 'PUSH')
    ),
    CONSTRAINT notification_preferences_mode_known CHECK (
        delivery_mode IN ('OFF', 'IMMEDIATE', 'DIGEST')
    ),
    -- An in-app inbox is already a list of things in one place, so "combine
    -- these into one message" has no meaning for it and no job will ever act on
    -- it. Refused here as well as in the application, because a row nothing can
    -- honour is a setting that lies to the person who set it.
    CONSTRAINT notification_preferences_in_app_does_not_digest CHECK (
        channel <> 'IN_APP' OR delivery_mode <> 'DIGEST'
    )
);

COMMENT ON TABLE notification_preferences IS
    '§4.10 (#85): per-user, per-category, per-channel standing instructions. Absence of a row means "no preference expressed" and the default in DeliveryPolicy applies -- deliberately not seeded.';
COMMENT ON COLUMN notification_preferences.category IS
    'Per category rather than per notification type: §4.10''s last line. Twenty-two switches per channel is a settings page nobody finishes.';
COMMENT ON COLUMN notification_preferences.delivery_mode IS
    'OFF, IMMEDIATE, or DIGEST. One column because the three are alternatives; a row that is both off and digesting would have to be interpreted by somebody.';

-- ---------------------------------------------------------------------------
-- notifications -- one attempted delivery
-- ---------------------------------------------------------------------------
CREATE TABLE notifications (
    id              uuid        PRIMARY KEY,

    -- Who is being told. Cascades: an inbox belongs to its account and to
    -- nothing else.
    recipient_id    uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,

    -- Which of §4.10's twenty-two rows this is, and which category it falls in.
    -- The category is stored rather than derived at read time so that a
    -- notification keeps the grouping it was sent under: moving a type between
    -- categories later must not silently rewrite what somebody was told.
    type            text        NOT NULL,
    category        text        NOT NULL,

    channel         text        NOT NULL,

    -- **The outbox event this was fanned out from.** The key the consumer
    -- deduplicates on -- see the header -- and, in an incident, what tells a
    -- redelivery apart from a genuine second event.
    --
    -- No foreign key to `outbox_events`, for V19's own reason about
    -- `aggregate_id`: an event is prunable once published, and a notification is
    -- a statement to a person that stays true afterwards. A cascade here would
    -- quietly empty somebody's inbox when the queue was tidied.
    event_id        uuid        NOT NULL,

    -- What the notification is about, as a type and an identifier rather than a
    -- foreign key -- `project`, `pledge`, `user` -- for the reason
    -- `outbox_events.aggregate_type` gives: no single reference can point at four
    -- tables, and reaching into another module's table from here is the coupling
    -- ModuleBoundaryTests exists to prevent arriving through the schema instead.
    -- Nullable together: a notification about nothing in particular is possible.
    subject_type    text,
    subject_id      uuid,

    -- What a renderer needs that it cannot look up: the campaign's title at the
    -- time, the amount that was pledged. jsonb rather than text, unlike
    -- `outbox_events.payload`, and the difference is real -- nothing re-reads
    -- these bytes as an event, and a template that wants one key should not have
    -- to parse the document to find it.
    --
    -- **Money in here is §10.3's object with the amount as a string**, never a
    -- JSON number. jsonb would happily store 0.1 as a float and hand back
    -- something that is not what was pledged.
    params          jsonb       NOT NULL DEFAULT '{}'::jsonb,

    -- PENDING -> SENT, PENDING -> DEAD, or HELD for a digest. See the header for
    -- why nothing drains HELD yet.
    state           text        NOT NULL DEFAULT 'PENDING',

    -- Sends attempted, not sends failed. The same column, meaning, and policy as
    -- `outbox_events.attempts`.
    attempts        integer     NOT NULL DEFAULT 0,
    last_error      text,

    -- When the sender may next try. Written by the application from the injected
    -- Clock and never defaulted here, for V19's reason: the backoff is a rule, a
    -- rule needs one home, and a test has to be able to ask what happens ten
    -- minutes later without waiting ten minutes.
    next_attempt_at timestamptz NOT NULL,

    -- **When the thing being reported happened**, taken from the event and not
    -- from the clock. A notification produced by an event redelivered an hour
    -- late still describes something that happened an hour ago, and an inbox
    -- ordered by when the row was written would put it in the wrong place.
    occurred_at     timestamptz NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),

    -- When it went to its channel. Null until it has.
    sent_at         timestamptz,

    -- When the recipient opened it. In-app only: there is no honest way to know
    -- whether an email or a push notification was read, and a tracking pixel is
    -- not one.
    read_at         timestamptz,

    -- The whole of the redelivery guarantee. See the header.
    CONSTRAINT notifications_event_recipient_channel_key UNIQUE (event_id, recipient_id, channel),
    CONSTRAINT notifications_type_known CHECK (
        type IN (
            'PLEDGE_CONFIRMED', 'PLEDGE_EDITED',
            'GOAL_REACHED', 'DEADLINE_48H', 'DEADLINE_24H',
            'CAMPAIGN_SUCCEEDED', 'CAMPAIGN_UNSUCCESSFUL', 'PROJECT_APPROVED',
            'PAYMENT_COLLECTED', 'PAYMENT_FAILED', 'FINAL_PAYMENT_WARNING', 'PAYOUT_SENT',
            'NEW_UPDATE_PUBLISHED', 'COMMENT_REPLY', 'DIRECT_MESSAGE',
            'SURVEY_AVAILABLE', 'SURVEY_OVERDUE', 'REWARD_SHIPPED',
            'FOLLOWED_CREATOR_LAUNCHED', 'LAUNCH_REMINDER', 'SAVED_PROJECT_ENDING_SOON',
            'NEW_DEVICE_SIGN_IN')
    ),
    CONSTRAINT notifications_category_known CHECK (
        category IN ('PLEDGES', 'CAMPAIGN', 'PAYMENTS', 'COMMUNITY', 'REWARDS', 'DISCOVERY', 'SECURITY')
    ),
    CONSTRAINT notifications_channel_known CHECK (
        channel IN ('IN_APP', 'EMAIL', 'PUSH')
    ),
    CONSTRAINT notifications_state_known CHECK (
        state IN ('PENDING', 'HELD', 'SENT', 'DEAD')
    ),
    CONSTRAINT notifications_attempts_is_not_negative CHECK (attempts >= 0),
    -- Both directions, as `outbox_events_published_when_it_says_so` insists. A
    -- SENT row with no instant cannot answer "was this sent before the
    -- incident", and an unsent row carrying one claims a delivery that has not
    -- happened.
    CONSTRAINT notifications_sent_when_it_says_so CHECK (
        (state = 'SENT') = (sent_at IS NOT NULL)
    ),
    -- Abandoned for a reason nobody wrote down, after a number of attempts
    -- nobody counted, is not a dead letter -- it is a disappearance.
    CONSTRAINT notifications_dead_letters_say_why CHECK (
        state <> 'DEAD' OR (last_error IS NOT NULL AND attempts >= 1)
    ),
    -- Read state is in-app only, and a message cannot be read before it was
    -- delivered.
    CONSTRAINT notifications_only_the_inbox_is_read CHECK (
        read_at IS NULL OR (channel = 'IN_APP' AND sent_at IS NOT NULL)
    ),
    -- The other half of notification_preferences_in_app_does_not_digest: an
    -- in-app notification is never held for a digest, because no digest of it
    -- would ever be produced.
    CONSTRAINT notifications_in_app_is_not_held CHECK (
        state <> 'HELD' OR channel <> 'IN_APP'
    ),
    -- A subject is a pair or it is absent. Half of one is a reference nothing
    -- can follow.
    CONSTRAINT notifications_subject_is_whole CHECK (
        (subject_type IS NULL) = (subject_id IS NULL)
    ),
    CONSTRAINT notifications_subject_type_length CHECK (
        subject_type IS NULL OR length(btrim(subject_type)) BETWEEN 1 AND 32
    ),
    -- The params are a document, not a scalar. An array or a bare string here
    -- would be a template reading `params->>'amount'` and getting null forever.
    CONSTRAINT notifications_params_is_an_object CHECK (jsonb_typeof(params) = 'object')
);

-- **The sender's own queue**, partial over the only state it claims. The queue is
-- short by design -- the job drains it -- so an ordered walk of the pending rows
-- is the right plan and stays right as the table grows, because everything
-- already sent has left this index.
CREATE INDEX notifications_queue_idx
    ON notifications (next_attempt_at, id) WHERE state = 'PENDING';

-- The inbox: this person's in-app notifications, newest first. Ordered by when
-- the thing happened rather than when the row was written, which is the same
-- column the reader is shown.
CREATE INDEX notifications_inbox_idx
    ON notifications (recipient_id, occurred_at DESC, id DESC)
    WHERE channel = 'IN_APP' AND state = 'SENT';

-- The unread count, which is the one number an inbox badge is made of.
CREATE INDEX notifications_unread_idx
    ON notifications (recipient_id)
    WHERE channel = 'IN_APP' AND state = 'SENT' AND read_at IS NULL;

-- What a digest job would claim: this person's held notifications on one
-- channel, oldest first. See the header for why the job is not here.
CREATE INDEX notifications_held_idx
    ON notifications (recipient_id, channel, occurred_at) WHERE state = 'HELD';

-- "What has stopped moving", asked by an operator and by whatever alerting §18.3
-- grows. Partial over a set that should be empty, which is what makes the
-- question cheap to ask often against a table that will be large.
CREATE INDEX notifications_dead_idx ON notifications (created_at) WHERE state = 'DEAD';

COMMENT ON TABLE notifications IS
    '§4.10 and §12.2 (#85): one row per (event, recipient, channel). Written inside the outbox dispatch transaction; sent afterwards by a queue with the same retry policy as outbox_events.';
COMMENT ON COLUMN notifications.event_id IS
    'The outbox event this was fanned out from. With recipient and channel it is the uniqueness that makes a redelivery produce nothing.';
COMMENT ON COLUMN notifications.params IS
    'Rendering data a template cannot look up. Money in here is §10.3''s {amount, currency} object with the amount as a string, never a JSON number.';
COMMENT ON COLUMN notifications.state IS
    'PENDING -> SENT or DEAD. HELD is a digest waiting for a combining job that does not exist yet -- see V26''s header.';
COMMENT ON COLUMN notifications.occurred_at IS
    'When the reported thing happened, from the event. Not when the row was written: an event delivered late still describes the past.';
