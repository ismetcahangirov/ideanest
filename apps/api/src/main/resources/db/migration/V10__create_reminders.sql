-- Launch reminders: the people who asked to be told when a campaign opens, and
-- the record of whether they were told.
--
-- §7.2 lists `reminders` beside `saves` and `follows` as a "backer signal", and
-- the three are not the same shape. A save and a follow belong to an account and
-- to nothing else. A reminder is the one signal a creator collects *before* the
-- campaign exists publicly, from people who have no reason to have registered
-- yet -- which is the entire point of a pre-launch page (§4.6, C-11). A table
-- that could only hold `user_id` would turn "notify me" into "create an account
-- first", and the followers a pre-launch page exists to collect are exactly the
-- people who will not do that.
--
-- So one row is one *identity that asked*, and there are two kinds:
--
--   `user_id`  a signed-in account. The address is not copied here; it is read
--              from `users` when the message is sent, so §17.4 anonymisation
--              reaches it without this table having to be swept.
--   `email`    somebody with no account. The address is here because there is
--              nowhere else it could be.
--
-- Exactly one of the two, never both and never neither -- `reminders_one_identity`
-- below. The alternative considered was a single denormalised `email` column
-- filled in for both kinds, which would have made deduplication trivial (one
-- address, one row, whoever registered it). It was rejected on §17.4: it copies a
-- registered person's address into a second table, and the copy is what still
-- holds their address after `users.email` has been overwritten by anonymisation.
-- The duplicate that the XOR shape allows -- somebody who signs up while signed
-- out and again while signed in -- is closed in the application instead, where a
-- signed-in registration removes the anonymous row for that account's *verified*
-- address. That is the one case where the two identities are provably one person.
--
-- Withdrawal is a DELETE, not a soft delete, and that is a deliberate departure
-- from §7.3. Soft delete is there for audit and recovery, and there is nothing to
-- audit about "somebody asked not to be emailed" beyond not emailing them --
-- keeping their address in order to remember that they left is exactly the
-- retention §17.4 refuses. Nothing references this table, so there is no
-- referential reason to keep the row either.
--
-- Reverse:
--   DROP TABLE IF EXISTS reminders;
--   -- Reversing discards every outstanding reminder and every record of one
--   -- having been sent. Nothing references this table, so the way back from a
--   -- bad release is the previous build of the application plus this line. Note
--   -- what it costs: a campaign that launched mid-incident and had notified half
--   -- its followers loses the record of which half, and re-running the sweep
--   -- after a restore would notify the first half twice. There is no way around
--   -- that -- the delivery state and the subscription are the same row on
--   -- purpose, because a delivery record that could outlive its subscription
--   -- would be a delivery record for somebody who had already left.

CREATE TABLE reminders (
    id             uuid        PRIMARY KEY,
    -- Cascades: a hard-deleted campaign is one that never launched, and a
    -- reminder about a campaign that does not exist can never be delivered.
    project_id     uuid        NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    -- The signed-in half of the identity. No ON DELETE clause, for the reason
    -- `projects.creator_id` has none: §17.4 closes an account by anonymising the
    -- person in place, which leaves this reference intact and pointing at a row
    -- whose address is now `deleted-<id>@anonymised.invalid` -- an address the
    -- sender resolves, finds unusable, and skips.
    user_id        uuid        REFERENCES users (id),
    -- The half for somebody with no account. citext, so that Person@Example.com
    -- and person@example.com are one reminder rather than two messages. The same
    -- decision as `users.email` and `collaborators.invited_email`, and it has to
    -- be the same one: the signed-in merge described above compares this against
    -- an account's address.
    email          citext,
    -- SHA-256 of the token in the "stop reminding me" link, never the token
    -- itself. Present for both kinds of row, not just the anonymous one: the
    -- launch email needs a working unsubscribe regardless of whether the
    -- recipient happens to be signed in when they read it, and a link that only
    -- works after signing in is not an unsubscribe link.
    --
    -- The `verification_tokens` reasoning applies unchanged: whoever can read
    -- this table must not be able to use what they find, and a 256-bit value we
    -- generated needs no salt and no work factor because there is no dictionary
    -- to attack it with.
    --
    -- **Minted when the message is, not when the reminder is.** A token created at
    -- registration would have to be handed back in the response for anybody to
    -- ever use it, and that response is the answer to an unauthenticated request
    -- carrying an arbitrary address -- so returning anything that differed
    -- between a first registration and a repeat would answer "does this address
    -- follow this campaign" for whoever asked. Minting it here, in the same
    -- statement that stamps `notified_at`, makes the link and the message that
    -- carries it one fact and keeps the registration response identical every
    -- time. What it costs is stated in `PrelaunchService`: until #86 sends a
    -- confirmation mail there is no way for somebody with no account to withdraw
    -- before the campaign launches.
    unsubscribe_token_hash bytea,
    -- When the launch notice for this row was handed to the sender.
    --
    -- **This column is what makes sending resumable.** The sweep claims a row by
    -- setting it inside the same transaction that sends, so a crash leaves every
    -- unsent row unclaimed and the next sweep continues from there; and a row
    -- that is already stamped is never picked up again, so a campaign whose
    -- launch was retried does not notify the same person twice. Null means "has
    -- not been told yet", which is also the state every row starts in.
    notified_at    timestamptz,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),

    -- One identity per row. Stated as an exclusive or rather than as two
    -- nullable columns with a comment, in the voice of
    -- `collaborators_acceptance_names_the_account`: the states that are
    -- incoherent are "a reminder for nobody", which can never be delivered, and
    -- "a reminder for an account *and* an address", which is one person asking
    -- once and being counted twice.
    CONSTRAINT reminders_one_identity CHECK ((user_id IS NULL) <> (email IS NULL)),
    -- The same loose shape as `users.email_shape`. The real test of an address is
    -- whether the message arrives at it.
    CONSTRAINT reminders_email_shape CHECK (
        email IS NULL OR email ~ '^[^@[:space:]]+@[^@[:space:]]+\.[^@[:space:]]+$'
    ),
    CONSTRAINT reminders_email_length CHECK (
        email IS NULL OR length(email::text) BETWEEN 3 AND 254
    ),
    CONSTRAINT reminders_token_hash_is_sha256 CHECK (
        unsubscribe_token_hash IS NULL OR octet_length(unsubscribe_token_hash) = 32
    ),
    -- A notified row has a working way out, and a row nobody has written to has
    -- nothing to be unsubscribed from. Stated as an equivalence rather than as
    -- two one-way checks, in the voice of
    -- `collaborators_acceptance_names_the_account`: the incoherent states are "we
    -- told somebody and gave them no way to stop" -- which is the definition of
    -- the mail people report -- and "a live unsubscribe link for a message that
    -- was never sent".
    CONSTRAINT reminders_notice_carries_its_way_out CHECK (
        (notified_at IS NULL) = (unsubscribe_token_hash IS NULL)
    ),
    CONSTRAINT reminders_notification_follows_the_request CHECK (
        notified_at IS NULL OR notified_at >= created_at
    )
);

-- **Idempotency lives here, not in the service.** Registering twice is one of
-- the two things this feature has to get right, and a read-then-write check in
-- Java loses the race between two clicks on a slow connection -- both read no
-- row, both insert, and the campaign now owes that person two emails. The
-- endpoint inserts with ON CONFLICT DO NOTHING and lets the database decide,
-- which it can only do if the rule is stated here.
--
-- Partial rather than plain, because the column is nullable on the other half of
-- the XOR: in PostgreSQL every NULL is distinct, so a plain unique index on
-- (project_id, user_id) would permit unlimited anonymous rows and quietly stop
-- being the constraint it looks like.
CREATE UNIQUE INDEX reminders_account_key
    ON reminders (project_id, user_id)
    WHERE user_id IS NOT NULL;

CREATE UNIQUE INDEX reminders_email_key
    ON reminders (project_id, email)
    WHERE email IS NOT NULL;

-- The unsubscribe lookup, and the reason a leaked link is worth one reminder and
-- not a way in: unique, so one hash is one row, and the hash is all the link
-- proves. Partial, because every unnotified row has a null here and PostgreSQL
-- would index all of them for a lookup that can never match one.
CREATE UNIQUE INDEX reminders_unsubscribe_token_hash_key
    ON reminders (unsubscribe_token_hash)
    WHERE unsubscribe_token_hash IS NOT NULL;

-- The sweep's query: everybody on this campaign who has not been told yet.
-- Partial on exactly those rows, because once a launch has been notified the
-- index for it is dead weight on every subsequent write -- and the pattern here
-- is a table that fills up over weeks and drains once.
CREATE INDEX reminders_pending_idx
    ON reminders (project_id, id)
    WHERE notified_at IS NULL;

-- "How many people are waiting", which the pre-launch page and the creator's
-- pre-launch tab both show, and which is counted on every view of either.
CREATE INDEX reminders_project_idx ON reminders (project_id);

CREATE TRIGGER reminders_set_updated_at
    BEFORE UPDATE ON reminders
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON TABLE reminders IS
    'Who asked to be told when a campaign opens. One identity per row: an account, or an address with no account behind it.';
COMMENT ON COLUMN reminders.notified_at IS
    'Claimed and stamped inside the transaction that sends. Null means not told yet; this is what makes the sweep resumable.';
COMMENT ON COLUMN reminders.unsubscribe_token_hash IS
    'SHA-256 of the unsubscribe token, minted with the launch notice. The token itself exists only in the message.';
