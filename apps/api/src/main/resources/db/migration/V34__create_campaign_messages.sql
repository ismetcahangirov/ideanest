-- §4.7's CD-13 (#98): a message a creator sends to their backers, or to a saved
-- segment of them.
--
-- ---------------------------------------------------------------------------
-- WHY THERE IS A ROW AT ALL
-- ---------------------------------------------------------------------------
--
-- The notifications this produces are the delivery; this table is the *act*.
-- Three things need it and none of them is served by the notification rows:
--
--   * The audit trail #98 asks for. `audit_logs` records that somebody messaged
--     a segment and cannot record what they said -- §17.4 keeps content out of
--     the one table with no retention rule, and audit detail is a summary line
--     rather than a document.
--   * The creator's own record. "What did we send, to whom, when" is the first
--     question after a message goes to the wrong people, and reconstructing it
--     from `notifications` means reading five thousand rows to recover one
--     subject line.
--   * Idempotency. The send is one transaction that writes this row and one
--     outbox event; without a row there is nothing for a retried request to
--     collide with.
--
-- ---------------------------------------------------------------------------
-- THE SEGMENT IS A SNAPSHOT, NOT A JOIN
-- ---------------------------------------------------------------------------
--
-- `segment_id` has **no foreign key**, and `segment_name` is copied in beside
-- it. That is V21's and V23's decision about `entity_id` and `target_id`, and
-- the reasons here are the two they give plus one of this feature's own:
--
--   * The record has to outlive what it refers to. A segment deleted after the
--     message went does not un-send it, and a message row that vanished with it
--     would take the audit trail with it.
--   * A segment's definition is editable. "Our German backers" today is not
--     necessarily the filter it was in March, so a live join would report this
--     message as having gone to a set it did not go to. `recipient_count` is
--     what actually went, frozen.
--   * The name is what a support conversation is held in. Nobody asks "who did
--     segment 8f3a… receive".
--
-- `NULL` means the message went to every backer of the campaign, which is a
-- different thing from a segment that happened to match everybody: it needed no
-- VIEW_FINANCES, because choosing nobody in particular reveals nothing about
-- who the backers are.
--
-- ---------------------------------------------------------------------------
-- WHY THE BODY IS SHORT, AND WHY THAT IS A PRODUCT DECISION
-- ---------------------------------------------------------------------------
--
-- 2,000 characters. A campaign that wants to write at length has `project_updates`
-- (CD-12), which stores the text **once** and serves it from a page. A bulk
-- message is copied into the rendering document of every notification it
-- produces -- per recipient, per channel -- so an unbounded body is an
-- unbounded multiplication, and the platform's audience ceiling is five
-- thousand.
--
-- So the bound is not an arbitrary limit dressed up: long-form belongs in an
-- update, and a message is the short targeted note that an update cannot be.
--
-- ---------------------------------------------------------------------------
-- Reverse:
--   DROP TABLE IF EXISTS campaign_messages;
--   -- Reversing discards the record of every message sent and the audit trail
--   -- built on it. The notifications it produced are not affected -- they are
--   -- their own rows and were already delivered -- so what is lost is the
--   -- ability to say what was sent and to whom, which is the whole reason the
--   -- table exists. `audit_logs` keeps the summary line, which refuses DELETE.
-- ---------------------------------------------------------------------------

CREATE TABLE campaign_messages (
    id              uuid        PRIMARY KEY,
    -- Cascades with the campaign, unlike the segment reference below: a message
    -- about a campaign that has been hard deleted is a message about nothing,
    -- and §7.3 only hard deletes a campaign that never launched -- which is a
    -- campaign with no backers to have messaged.
    project_id      uuid        NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    -- Which saved segment, or NULL for every backer. No foreign key: see above.
    segment_id      uuid,
    -- What that segment was called when the message went. NULL exactly when
    -- `segment_id` is, so the pair is whole or absent together and no reader has
    -- to guess whether a missing name means "everybody" or "deleted".
    segment_name    text,
    -- No ON DELETE, for the reason `saves.user_id` has none: §17.4 anonymises in
    -- place, and the reference stays pointing at a row that is no longer
    -- anybody. Who sent a message is a fact the record needs even then.
    sent_by         uuid        NOT NULL REFERENCES users (id),
    subject         text        NOT NULL,
    body            text        NOT NULL,
    -- How many people it actually reached, frozen. Not derivable later: the
    -- segment's definition can change and the campaign's backers certainly will.
    recipient_count integer     NOT NULL,
    -- Whether the audience hit the platform's ceiling, so that a message which
    -- reached the first five thousand of six thousand backers says so here
    -- rather than looking like a message that reached everybody. The same
    -- honesty CD-11's export header carries about a truncated file.
    truncated       boolean     NOT NULL DEFAULT false,
    created_at      timestamptz NOT NULL DEFAULT now(),

    -- Whole or absent together. Stated as an equivalence rather than two checks,
    -- in the voice of `reminders_notice_carries_its_way_out`: the incoherent
    -- states are a segment with no name, which no support query can resolve, and
    -- a name with no segment, which claims a target the message did not have.
    CONSTRAINT campaign_messages_segment_is_whole CHECK (
        (segment_id IS NULL) = (segment_name IS NULL)
    ),
    CONSTRAINT campaign_messages_subject_length CHECK (
        length(btrim(subject)) BETWEEN 1 AND 150
    ),
    -- See the header. The service refuses the same bound with a sentence; this
    -- is what refuses it under a support script.
    CONSTRAINT campaign_messages_body_length CHECK (
        length(btrim(body)) BETWEEN 1 AND 2000
    ),
    CONSTRAINT campaign_messages_segment_name_length CHECK (
        segment_name IS NULL OR length(btrim(segment_name)) BETWEEN 1 AND 80
    ),
    -- A message that reached nobody is an ordinary outcome -- a segment matching
    -- no backer today -- so zero is allowed and negative is not.
    CONSTRAINT campaign_messages_recipient_count_is_not_negative CHECK (recipient_count >= 0)
);

-- "What have we sent", newest first, which is the only read this table has.
-- Keyset on (created_at, id) like every other page in this schema.
CREATE INDEX campaign_messages_project_idx
    ON campaign_messages (project_id, created_at DESC, id DESC);

COMMENT ON TABLE campaign_messages IS
    'CD-13: a message sent to a campaign''s backers or to a saved segment of them. The act; the notifications it produced are the delivery.';
COMMENT ON COLUMN campaign_messages.segment_id IS
    'Which saved segment, or NULL for every backer. Deliberately not a foreign key: the record outlives the segment, and the segment''s definition can be edited after the message went.';
COMMENT ON COLUMN campaign_messages.recipient_count IS
    'How many people it reached, frozen. Not derivable later -- the segment and the campaign''s backers both move.';
