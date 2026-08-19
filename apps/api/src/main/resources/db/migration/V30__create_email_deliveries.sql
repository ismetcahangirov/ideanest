-- #86's delivery record: what the email transport did, one row per attempt.
--
-- ---------------------------------------------------------------------------
-- WHAT THIS TABLE KNOWS, AND WHAT IT DELIBERATELY DOES NOT
-- ---------------------------------------------------------------------------
--
-- The transport is SMTP (§16, `spring.mail`). An SMTP relay answers one
-- question and only one: whether it accepted the message. It does not say
-- whether the message arrived, whether it was filed as spam, whether it
-- bounced afterwards, or whether anybody opened it -- those are facts a
-- provider returns later, over a webhook, and there is no provider and no
-- webhook here.
--
-- So the vocabulary is chosen to be unable to overstate what happened:
--
--   * The column is `accepted_at`, not `delivered_at`. A column called
--     `delivered_at` would be read as delivery by everybody who ever queried
--     it, and it would be wrong every time the relay accepted a message it
--     later failed to deliver.
--   * `outcome` has three values and none of them is DELIVERED or BOUNCED.
--     Adding either is a migration and a provider integration, in that order.
--
-- Bounce handling, suppression lists and open tracking are named in
-- `ChannelSender` as #86's, and they are the part of it that cannot be built
-- against a relay. They are follow-up work, recorded as such rather than
-- half-built here: a `bounced_at` column nothing ever writes is a column every
-- future reader will assume is maintained.
--
-- ---------------------------------------------------------------------------
-- ONE ROW PER ATTEMPT, NOT PER NOTIFICATION
-- ---------------------------------------------------------------------------
--
-- `notifications` already holds the current state of one message -- its
-- `attempts`, its `last_error`, whether it is a dead letter. What it cannot
-- hold is the history: which attempt failed, when, and with what answer from
-- the relay. That history is what somebody has when a person says they never
-- received a payment failure, and it is the whole reason this table is append
-- only.
--
-- The consequence is that a notification retried eight times has eight rows
-- here, and the last one is its outcome. `email_deliveries_notification_idx`
-- is what makes reading them back cheap.
--
-- ---------------------------------------------------------------------------
-- THERE IS NO ADDRESS COLUMN, AND THAT IS NOT AN OVERSIGHT
-- ---------------------------------------------------------------------------
--
-- The obvious column here is `to_address`, and it is deliberately absent.
--
-- §17.4's anonymisation rewrites `users.email` to
-- `deleted-<id>@anonymised.invalid` when an account is deleted. An address
-- copied into this table would survive that -- a log of every address the
-- platform ever wrote to, retained after the person asked to be forgotten and
-- outside the one place the anonymiser knows to look. Closing that would mean
-- teaching the anonymiser about this table, which is a second place the rule
-- has to be remembered.
--
-- `recipient_id` answers the question that is actually asked in support --
-- "did we email this person, and did it go out" -- and it resolves to the
-- current address through `users` for exactly as long as there is a person to
-- resolve it to. When there is not, this table correctly no longer knows.
--
-- ---------------------------------------------------------------------------
-- DIGESTS
-- ---------------------------------------------------------------------------
--
-- §12.2's digest is one message about several notifications, so it has no
-- single `notification_id`. It carries `digest_id` instead -- the key
-- `NotificationDigest` derives from its members, stable across attempts and
-- therefore the same idempotency key the single-message path uses -- plus how
-- many notifications went into it.
--
-- Exactly one of the two is set, which `email_deliveries_is_one_or_the_other`
-- enforces rather than leaving to the writer.
--
-- ---------------------------------------------------------------------------
-- ROLLING DEPLOYMENT
-- ---------------------------------------------------------------------------
--
-- One new table and four new indexes. Nothing existing is touched: no column is
-- dropped, no constraint is added to an existing table, and no previous release
-- reads or writes anything here -- the release before this one registers
-- `UndeliverableChannelSender` for EMAIL, which writes a log line and nothing
-- else. Both halves of a rolling deploy are safe in either order. This is an
-- EXPAND with no contract half.
--
-- Reverse:
--   DROP TABLE IF EXISTS email_deliveries;
--
--   **Roll the application back first, then drop the table.** In that order it
--   is safe against any release that does not send email, which is every
--   release before this one, and it discards only the record of what the
--   transport did while it ran -- the mail itself was accepted by a relay and
--   nothing about that is undone by a DROP.
--
--   In the other order it is actively harmful, and the reason is worth stating
--   because it is not obvious. `EmailChannelSender` sends and then records, both
--   inside the transaction that claims the notification. With the table gone the
--   insert fails, that transaction rolls back, and the notification is left
--   PENDING -- having already been accepted by the relay. The next pass sends it
--   again, and so does the one after that. A missing table would therefore not
--   silence the log; it would mail somebody the same message every second until
--   an operator noticed.

CREATE TABLE email_deliveries (
    id              uuid        PRIMARY KEY,

    -- The notification this attempt was for, or null when it was a digest.
    -- Cascades: an attempt to tell somebody something is meaningless once the
    -- something is gone.
    notification_id uuid        REFERENCES notifications (id) ON DELETE CASCADE,

    -- The digest this attempt was for, or null when it was a single message.
    --
    -- No foreign key, because a digest is not a row anywhere: it is a value
    -- derived from the set of held notifications that were combined, and its
    -- identifier is a hash of their identifiers. See `NotificationDigest.of`.
    digest_id       uuid,

    -- How many notifications the message covered. One for a single message,
    -- and the member count for a digest. Kept because the members themselves
    -- are recoverable only while their rows live, and "the digest we sent them
    -- on Tuesday covered nine things" is the answer support needs.
    member_count    integer     NOT NULL DEFAULT 1,

    -- Who it was for. The join to an address -- see the header for why the
    -- address itself is not here.
    recipient_id    uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,

    -- Which of §4.10's rows this was, for a single message. Null for a digest,
    -- which is by definition several types at once.
    type            text,

    -- ACCEPTED, REFUSED or SUPPRESSED. See the header: what is missing from
    -- this list is the point of it.
    outcome         text        NOT NULL,

    -- Which send this was, counted from one and matching `notifications.attempts`
    -- at the moment of the attempt. A digest charges one attempt against every
    -- member, so every row in the group shares this number.
    attempt         integer     NOT NULL,

    -- The subject line as it went out, and the RFC 5322 Message-ID it carried.
    --
    -- The Message-ID is derived from the notification (or digest) identifier,
    -- which `ChannelSender` documents as stable across every attempt. It is
    -- recorded because it is the only handle a relay's own logs and a
    -- recipient's mail client share with this table -- given a complaint that
    -- names a message, this is how it is found.
    --
    -- Both are null on a SUPPRESSED row: nothing was rendered, because the
    -- decision not to send is taken before the template is.
    subject         text,
    message_id      text,

    -- Why, for the two outcomes that have a why. The relay's refusal, or which
    -- rule suppressed the send. Null on ACCEPTED, where the outcome is the
    -- whole of the fact.
    detail          text,

    -- When the relay took it. Null unless it did. **Not `delivered_at`** -- see
    -- the header.
    accepted_at     timestamptz,

    created_at      timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT email_deliveries_outcome_known CHECK (
        outcome IN ('ACCEPTED', 'REFUSED', 'SUPPRESSED')
    ),
    CONSTRAINT email_deliveries_is_one_or_the_other CHECK (
        (notification_id IS NULL) <> (digest_id IS NULL)
    ),
    CONSTRAINT email_deliveries_attempt_is_counted_from_one CHECK (attempt >= 1),
    CONSTRAINT email_deliveries_covers_something CHECK (member_count >= 1),
    -- A single message covers one notification and names its type; a digest
    -- covers several and names none. Enforced rather than trusted, because a
    -- row that claimed both would be one nothing downstream could render.
    CONSTRAINT email_deliveries_single_message_has_a_type CHECK (
        (notification_id IS NULL) = (type IS NULL)
    ),
    -- An accepted message was rendered and stamped, and it was accepted at some
    -- moment. Anything else claiming acceptance is a row that says a person was
    -- written to and cannot say what was sent.
    CONSTRAINT email_deliveries_accepted_says_what_and_when CHECK (
        outcome <> 'ACCEPTED'
        OR (accepted_at IS NOT NULL AND subject IS NOT NULL AND message_id IS NOT NULL)
    ),
    -- The converse: only an accepted message has an acceptance time.
    CONSTRAINT email_deliveries_only_accepted_is_accepted CHECK (
        outcome = 'ACCEPTED' OR accepted_at IS NULL
    ),
    -- A refusal and a suppression both have to say why. "It did not go" with no
    -- reason is a row that answers no question anybody has.
    CONSTRAINT email_deliveries_failures_say_why CHECK (
        outcome = 'ACCEPTED' OR detail IS NOT NULL
    )
);

-- The support query: everything this notification's transport did, newest last.
CREATE INDEX email_deliveries_notification_idx
    ON email_deliveries (notification_id, created_at)
    WHERE notification_id IS NOT NULL;

-- The same for a digest.
CREATE INDEX email_deliveries_digest_idx
    ON email_deliveries (digest_id, created_at)
    WHERE digest_id IS NOT NULL;

-- "What have we sent this person" -- the question a support ticket opens with.
CREATE INDEX email_deliveries_recipient_idx
    ON email_deliveries (recipient_id, created_at DESC);

-- The operational one: what is not going out, and since when. Partial, because
-- the healthy case is the overwhelming majority of the table and an operator
-- never scans it.
CREATE INDEX email_deliveries_trouble_idx
    ON email_deliveries (created_at DESC)
    WHERE outcome <> 'ACCEPTED';

COMMENT ON TABLE email_deliveries IS
    'One attempt by the email transport, append only. Records acceptance by a relay, '
    'never delivery -- SMTP cannot report delivery, and no column here claims to.';
COMMENT ON COLUMN email_deliveries.accepted_at IS
    'When the relay accepted the message. Deliberately not named delivered_at: '
    'acceptance is all SMTP reports, and bounce handling needs a provider webhook.';
COMMENT ON COLUMN email_deliveries.message_id IS
    'The RFC 5322 Message-ID, derived from the notification or digest identifier and '
    'therefore stable across retries -- which is how a duplicate send is collapsible.';
COMMENT ON COLUMN email_deliveries.digest_id IS
    'The key NotificationDigest derives from its members. Not a foreign key: a digest '
    'is a value, not a row.';
