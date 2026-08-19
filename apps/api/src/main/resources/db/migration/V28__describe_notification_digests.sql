-- §8.4's `notification-digest` (#244): the job V26 said did not exist now does, so
-- the comments V26 wrote about that absence are wrong.
--
-- ---------------------------------------------------------------------------
-- WHY A MIGRATION FOR COMMENTS
-- ---------------------------------------------------------------------------
--
-- Nothing structural changes here. No column, no constraint, no index, no data.
-- What changes is what the schema says about itself, and this repository treats a
-- comment as part of the schema for a reason that is not pedantry: V26's comment
-- on `notifications.state` reads
--
--   'PENDING -> SENT or DEAD. HELD is a digest waiting for a combining job that
--    does not exist yet -- see V26''s header.'
--
-- and an operator reading that at three in the morning, while looking at a table
-- full of HELD rows that are about to be combined, would draw exactly the wrong
-- conclusion — that they had found the bug, and that the rows were stranded. A
-- documented invariant that has stopped being true is worse than an undocumented
-- one, because it is believed. `COMMENT ON` is the only way to correct it, and
-- `COMMENT ON` is DDL, so it is a migration.
--
-- ---------------------------------------------------------------------------
-- WHAT THE STATE MACHINE IS NOW
-- ---------------------------------------------------------------------------
--
-- Two producers write rows and two consumers drain them, and no row is ever a
-- candidate for both:
--
--   PENDING -> SENT | DEAD      claimed one row at a time by `notification-sender`
--   HELD    -> SENT | DEAD      claimed one *group* at a time by `notification-digest`
--
-- A digest is a grouping over (recipient_id, channel) rather than a row, which is
-- why the claim is two statements — an aggregate cannot be locked with FOR UPDATE —
-- and why `notification-digest` runs on the shared scheduler and needs its lease.
-- `DigestAssembly` argues that at length.
--
-- `notifications_held_idx` is unchanged and is now the index that claim actually
-- uses: V26 created it `(recipient_id, channel, occurred_at) WHERE state = 'HELD'`
-- for a job that did not exist, and it turns out to be the right index for the one
-- that does. `next_attempt_at` on a held row, which V26 described as "the column a
-- combining job would order by", is what the backoff of a refused digest writes.
--
-- ---------------------------------------------------------------------------
-- WHAT IS STILL NOT TRUE
-- ---------------------------------------------------------------------------
--
-- **Email and push still have no transport.** #86 and #87 own them and both are
-- registered as `UndeliverableChannelSender`, so a digest — like an immediate
-- notification on those channels — is written to a log line and the rows say SENT.
-- That is a missing transport rather than a missing digest, and it is the
-- distinction #244 was about: the holding no longer accumulates for ever with
-- nothing to drain it.
--
-- ---------------------------------------------------------------------------
-- Rolling deployment
-- ---------------------------------------------------------------------------
--
-- `COMMENT ON` takes no lock a reader or a writer can feel, changes no plan, and
-- is invisible to every query. Both halves of a rolling deploy are safe in either
-- order and neither release behaves differently.
--
-- The release running alongside this one is the one that starts draining HELD.
-- That is also safe in either order, and it is worth saying why rather than
-- assuming it: `notification-digest` takes a lease under its own name, so the old
-- release simply has no such job and the new one has it once across the fleet;
-- and the two queues are disjoint by state, so a sender from the old release
-- cannot claim a row a digest from the new one is holding.
--
-- Reverse:
--   The previous text, which is in V26 and in this file above. Restoring it is a
--   COMMENT ON and nothing more. It would be restoring a false statement, so the
--   honest reverse of this migration is to revert the code with it.

COMMENT ON COLUMN notifications.state IS
    'PENDING -> SENT or DEAD, claimed a row at a time by notification-sender. HELD -> SENT or DEAD, claimed a (recipient, channel) group at a time by notification-digest (#244). No row is a candidate for both.';

COMMENT ON COLUMN notifications.next_attempt_at IS
    'When this row is next eligible. For PENDING, the sender''s backoff. For HELD, the digest''s: a combined message a channel refuses moves every row in it to the same next attempt.';

COMMENT ON INDEX notifications_held_idx IS
    'The claim path for notification-digest: the oldest thing waiting per (recipient, channel), bounded by the digest period that has most recently closed.';
