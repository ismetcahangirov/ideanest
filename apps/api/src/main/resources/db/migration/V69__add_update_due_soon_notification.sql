-- One more notification type: the warning that comes before a §5.5 lapse. Issue #437.
--
-- ---------------------------------------------------------------------------
-- Reverse:
--   DELETE FROM notifications WHERE type = 'UPDATE_DUE_SOON';
--   ALTER TABLE notifications DROP CONSTRAINT notifications_type_known;
--   ALTER TABLE notifications ADD CONSTRAINT notifications_type_known CHECK (
--       type IN (
--           'PLEDGE_CONFIRMED', 'PLEDGE_EDITED',
--           'GOAL_REACHED', 'DEADLINE_48H', 'DEADLINE_24H',
--           'CAMPAIGN_SUCCEEDED', 'CAMPAIGN_UNSUCCESSFUL', 'PROJECT_APPROVED',
--           'PAYMENT_COLLECTED', 'PAYMENT_FAILED', 'FINAL_PAYMENT_WARNING', 'PAYOUT_SENT',
--           'NEW_UPDATE_PUBLISHED', 'COMMENT_REPLY', 'DIRECT_MESSAGE',
--           'SURVEY_AVAILABLE', 'SURVEY_OVERDUE', 'REWARD_SHIPPED',
--           'FOLLOWED_CREATOR_LAUNCHED', 'LAUNCH_REMINDER', 'SAVED_PROJECT_ENDING_SOON',
--           'NEW_DEVICE_SIGN_IN'));
--
--   The DELETE is not optional and it is not safe to skip: the narrowed
--   constraint is not satisfiable while a row of this type exists, so a reversal
--   that leaves them fails at the ALTER and leaves the table half-changed. What
--   it costs is a creator's inbox history, which is a notification and not a
--   record -- `update_obligations` still carries which cycles were claimed.
--
--   **Order the release, not just the statements.** Widening a vocabulary is
--   safe under rolling deployment only in this direction: the constraint goes
--   first and the code that writes the value follows. Reversing runs the same
--   sequence backwards, so the release that stops writing UPDATE_DUE_SOON must
--   be fully out before this migration is reversed -- `ProjectAudience` makes
--   the same argument about `deadline_notices.threshold_hours`.
-- ---------------------------------------------------------------------------
--
-- §5.5 obliges a creator to publish an update at least monthly after a
-- successful campaign, and #437 makes that a clock. A clock that only ever
-- produced a lapse would be a platform that waits quietly for somebody to fail;
-- #437 is explicit that "the point is compliance, not catching people", so the
-- warning goes out before the month is up and the lapse itself notifies nobody.
--
-- **This is the only notification the mechanism sends.** A lapse produces a
-- state on the campaign page and a row in a moderator's queue, and neither is a
-- message: telling a creator they are late immediately after warning them is the
-- platform saying the same thing twice with a worse tone, and telling their
-- backers would be the platform publishing a verdict about a dispute §9.7 says
-- it only mediates.
--
-- CAMPAIGN rather than a new category. §21 gives a reader one preference switch
-- per category, and a creator who wants campaign mail wants this; a category of
-- its own would be a switch whose only member is a reminder they cannot act on
-- by turning it off.

ALTER TABLE notifications
    DROP CONSTRAINT notifications_type_known;

ALTER TABLE notifications
    ADD CONSTRAINT notifications_type_known CHECK (
        type IN (
            'PLEDGE_CONFIRMED', 'PLEDGE_EDITED',
            'GOAL_REACHED', 'DEADLINE_48H', 'DEADLINE_24H',
            'CAMPAIGN_SUCCEEDED', 'CAMPAIGN_UNSUCCESSFUL', 'PROJECT_APPROVED',
            'UPDATE_DUE_SOON',
            'PAYMENT_COLLECTED', 'PAYMENT_FAILED', 'FINAL_PAYMENT_WARNING', 'PAYOUT_SENT',
            'NEW_UPDATE_PUBLISHED', 'COMMENT_REPLY', 'DIRECT_MESSAGE',
            'SURVEY_AVAILABLE', 'SURVEY_OVERDUE', 'REWARD_SHIPPED',
            'FOLLOWED_CREATOR_LAUNCHED', 'LAUNCH_REMINDER', 'SAVED_PROJECT_ENDING_SOON',
            'NEW_DEVICE_SIGN_IN'));
