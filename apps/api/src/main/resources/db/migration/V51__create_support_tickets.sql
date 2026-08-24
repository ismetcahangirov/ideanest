-- §4.11's AD-10 (#310): a place for the conversations that are not comments.
--
-- ---------------------------------------------------------------------------
-- Reverse:
--   DROP TABLE IF EXISTS support_ticket_messages;
--   DROP TABLE IF EXISTS support_tickets;
--
--   Order matters: the messages reference the tickets. Lossy, and the loss is
--   somebody's complaint and the platform's answer to it -- there is no other
--   copy, because a ticket is written here and nowhere else. Several of these
--   are the promise a refund decision later turns on.
-- ---------------------------------------------------------------------------
--
-- ---------------------------------------------------------------------------
-- WHY THE PLATFORM NEEDS ITS OWN AND CANNOT USE A MAILBOX
-- ---------------------------------------------------------------------------
--
-- §4.11 asks for "tickets with user context and action history", and the second
-- half is the reason a shared mailbox does not do. A support conversation about
-- a pledge is read beside that pledge, that account's standing, and what staff
-- have already done about it -- and an email client knows none of those. The
-- console screen this table exists for puts the ticket next to the account, so
-- the identifier has to be a column rather than a sentence somebody pasted.
--
-- ---------------------------------------------------------------------------
-- WHY TWO TABLES
-- ---------------------------------------------------------------------------
--
-- A ticket is state -- open, waiting, resolved, who has it -- and a message is
-- an append. Putting the latest message on the ticket row would make "the
-- history of what was said" unrecoverable, and putting the state on each message
-- would make "is this open" a question about the newest row.
--
-- The pair is `comments`/`content_reports` shaped, deliberately: one row that is
-- triaged, many rows that are written.
--
-- ---------------------------------------------------------------------------
-- WHY subject_type/subject_id AND NOT A NULLABLE project_id AND pledge_id
-- ---------------------------------------------------------------------------
--
-- The same argument `fee_schedules.scope` makes, and the same conclusion: a
-- ticket is about an account, a campaign, a pledge or nothing in particular, and
-- four nullable foreign keys with a CHECK that at most one is set is this shape
-- reached the long way. No FK on `subject_id`, because it points into different
-- tables -- a ticket about a campaign that was later deleted keeps its subject
-- and the screen shows the identifier, which is more useful than a null.
--
-- ---------------------------------------------------------------------------
-- WHY assignee_id IS NULLABLE AND THERE IS NO QUEUE TABLE
-- ---------------------------------------------------------------------------
--
-- Unassigned is the queue. A separate queue table would hold exactly the rows
-- where `assignee_id IS NULL` and would then need to be kept in step with them.
-- The partial index below is that queue, maintained by the database.
-- ---------------------------------------------------------------------------

CREATE TABLE support_tickets (
    id uuid PRIMARY KEY,

    -- Who is asking. Not nullable: an anonymous ticket has no account context,
    -- which is the whole of what this screen is for. Someone with no account who
    -- needs help is a mail to the published address, and that is a different
    -- product.
    requester_id uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,

    subject text NOT NULL
        CONSTRAINT support_tickets_subject_present CHECK (length(btrim(subject)) BETWEEN 1 AND 200),

    subject_type text NOT NULL DEFAULT 'NONE'
        CONSTRAINT support_tickets_subject_type_known CHECK (
            subject_type IN ('NONE', 'PROJECT', 'PLEDGE', 'ACCOUNT')),

    subject_ref uuid,

    CONSTRAINT support_tickets_subject_ref_matches_type CHECK (
        (subject_type = 'NONE' AND subject_ref IS NULL)
        OR (subject_type <> 'NONE' AND subject_ref IS NOT NULL)),

    -- OPEN -> PENDING (waiting on the requester) -> RESOLVED, and back to OPEN
    -- when they reply. CLOSED is terminal and is what a resolved ticket becomes
    -- when nobody replies; it is deliberately separate from RESOLVED so that
    -- "we answered and heard nothing" is distinguishable from "we answered and
    -- they were satisfied", which are different numbers in a support report.
    state text NOT NULL DEFAULT 'OPEN'
        CONSTRAINT support_tickets_state_known CHECK (state IN ('OPEN', 'PENDING', 'RESOLVED', 'CLOSED')),

    priority text NOT NULL DEFAULT 'NORMAL'
        CONSTRAINT support_tickets_priority_known CHECK (priority IN ('LOW', 'NORMAL', 'HIGH', 'URGENT')),

    -- Null is the queue. See the header.
    assignee_id uuid REFERENCES users (id) ON DELETE SET NULL,

    created_at timestamptz NOT NULL DEFAULT now(),

    updated_at timestamptz NOT NULL DEFAULT now(),

    resolved_at timestamptz,

    CONSTRAINT support_tickets_resolved_matches_state CHECK (
        (state IN ('RESOLVED', 'CLOSED')) = (resolved_at IS NOT NULL))
);

-- The queue, oldest first, highest priority first. A partial index because the
-- resolved ones are the majority within a month and are never in the queue.
CREATE INDEX support_tickets_open_queue
    ON support_tickets (priority DESC, created_at ASC)
    WHERE state IN ('OPEN', 'PENDING');

-- "What has this person asked us", which is the other half of the account
-- context the console screen shows.
CREATE INDEX support_tickets_by_requester
    ON support_tickets (requester_id, created_at DESC);

CREATE TABLE support_ticket_messages (
    id uuid PRIMARY KEY,

    ticket_id uuid NOT NULL REFERENCES support_tickets (id) ON DELETE CASCADE,

    author_id uuid NOT NULL REFERENCES users (id) ON DELETE RESTRICT,

    -- Whether this was written by the person asking or by the platform. Derived
    -- from the author in most cases and stored anyway, because a member of staff
    -- can also be a requester on their own ticket -- and the screen renders the
    -- two sides differently.
    author_side text NOT NULL
        CONSTRAINT support_ticket_messages_side_known CHECK (author_side IN ('REQUESTER', 'STAFF')),

    body text NOT NULL
        CONSTRAINT support_ticket_messages_body_present CHECK (length(btrim(body)) BETWEEN 1 AND 20000),

    -- A note staff leave for each other. Never shown to the requester, which is
    -- why it is a column on the message rather than a separate table nobody
    -- would remember to filter.
    internal boolean NOT NULL DEFAULT false,

    CONSTRAINT support_ticket_messages_internal_is_staff CHECK (
        NOT internal OR author_side = 'STAFF'),

    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX support_ticket_messages_by_ticket
    ON support_ticket_messages (ticket_id, created_at ASC);

COMMENT ON TABLE support_tickets IS
    'Support conversations with the account context they are about (#310, AD-10).';
