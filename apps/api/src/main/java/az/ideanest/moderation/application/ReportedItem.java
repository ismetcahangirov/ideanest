package az.ideanest.moderation.application;

import az.ideanest.moderation.domain.ReportTargetType;
import az.ideanest.shared.project.ProjectSummary;
import java.time.Instant;
import java.util.UUID;

/**
 * What a report is actually about — #399.
 *
 * <p><strong>The thing the moderation queues were asking about and never showing.</strong>
 * A moderator opening {@code /admin/moderation/{id}} was given the reporter's claim — "the
 * comment contains a free download link" — and not the comment, not who wrote it, not
 * which campaign it is on, and no link to any of the three. Upholding or dismissing on
 * that is a guess, and the cheap guess is always to dismiss, which is how a moderation
 * queue quietly stops working.
 *
 * <p><strong>One shape for four kinds of target</strong>, because the screen that renders
 * it is one screen. {@link #state} is what a client branches on, and the four values are
 * four genuinely different things to say to a moderator rather than a success flag with
 * decoration:
 *
 * @param state see {@link State}
 * @param title the update's headline. Null for a comment, which has none, and for the two
 *     kinds the console addresses directly
 * @param body what was written, verbatim. <strong>Untrusted</strong>: it is text one member
 *     of the public wrote, reaching a screen operated by staff, and it is rendered as text
 *     and never as markup
 * @param authorId who wrote it. Named through the console's own directory rather than here
 *     — #402 built that endpoint so that five response contracts did not each have to grow
 *     a name field, and this is the sixth that does not
 * @param number the update's per-campaign sequence, or zero where there is none. "Update 4"
 *     is what a creator and a backer both call it
 * @param project the campaign it belongs to, with the halves of its public path. Present
 *     for every kind but an account, which belongs to no campaign
 * @param createdAt when the content was written or published, which is not when the report
 *     was filed. A comment posted eight months ago and reported this morning is a different
 *     situation from one posted an hour ago, and only one of those two dates says which
 */
public record ReportedItem(
        ReportTargetType targetType,
        State state,
        String title,
        String body,
        UUID authorId,
        int number,
        ProjectSummary project,
        Instant createdAt) {

    /** What the platform can say about the reported thing. */
    public enum State {

        /** It is there and it is on the platform. The ordinary case. */
        PRESENT,

        /**
         * It is there and it has been taken down.
         *
         * <p>The body still comes back: V25 keeps a removed comment's row and its text so
         * that a report filed before the removal can still be decided, and a moderator who
         * is told only "removed" cannot tell an upheld report from a dismissed one. What
         * changes is that the screen says so, above the text, so that nobody removes it
         * twice or bans somebody for a comment somebody else has already handled.
         */
        REMOVED,

        /**
         * The identifier names nothing any more.
         *
         * <p>Hard deletion, §17.4's anonymisation, or a report about something that was
         * purged. Distinct from {@link #REMOVED} on purpose — "it was taken down" and "it
         * is not there at all" lead to different decisions — and distinct from a 404 on
         * this endpoint, which would say the <em>report</em> does not exist.
         */
        GONE,

        /**
         * The target is a campaign or an account, which the console reaches directly.
         *
         * <p>Not a failure and not an empty answer. A campaign has a staff preview at
         * {@code /admin/campaigns/{id}} that renders it in any state, and an account has a
         * public profile; both are named by the console's directory and linked from the
         * report. There is no separate blob of text to inline, and inventing one — a
         * campaign's blurb, an account's bio — would put a fragment of a page next to a
         * link to the page, which is worse than the link alone.
         */
        ADDRESSED_DIRECTLY
    }

    /** A comment, removed or not. */
    static ReportedItem comment(
            State state, String body, UUID authorId, ProjectSummary project, Instant createdAt) {
        return new ReportedItem(ReportTargetType.COMMENT, state, null, body, authorId, 0, project, createdAt);
    }

    /** An update on a campaign. */
    static ReportedItem update(
            String title, String body, UUID authorId, int number, ProjectSummary project, Instant publishedAt) {
        return new ReportedItem(
                ReportTargetType.PROJECT_UPDATE, State.PRESENT, title, body, authorId, number, project, publishedAt);
    }

    /** Nothing at that identifier. */
    static ReportedItem gone(ReportTargetType targetType) {
        return new ReportedItem(targetType, State.GONE, null, null, null, 0, null, null);
    }

    /** A campaign or an account: the console links to it rather than inlining it. */
    static ReportedItem addressedDirectly(ReportTargetType targetType, ProjectSummary project) {
        return new ReportedItem(targetType, State.ADDRESSED_DIRECTLY, null, null, null, 0, project, null);
    }
}
