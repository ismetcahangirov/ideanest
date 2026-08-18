package az.ideanest.audit;

/**
 * Every privileged action the platform records, and what each one is done to.
 *
 * <p><strong>The entity kind travels with the action rather than beside it.</strong>
 * A recording API that took {@code ("project.approved", "collaborator", id)} would
 * eventually be given exactly that, and the row would be invisible to the one query
 * anybody runs against this table — "what has happened to this campaign". Pairing
 * them here makes the mistake unspellable: a caller chooses a constant and supplies
 * an identifier, and nothing else about the row is theirs to get wrong.
 *
 * <p>A closed set in Java and open text in the column, which is deliberate and is
 * V19's argument about {@code outbox_events.event_type}. The set grows with every
 * feature that adds a privileged action, and a new one must not be a migration and
 * a deployment-ordering problem; what the enum buys is that the <em>writing</em>
 * side cannot invent a spelling, so a reader filtering on {@code project.approved}
 * is not silently missing rows that say {@code project_approved}.
 *
 * <p>The wire spelling is {@code <entity>.<past participle>}. Past tense because a
 * row is a statement about something that already happened — including when the
 * outcome is {@link AuditOutcome#REFUSED}, where what happened is the attempt.
 */
public enum AuditAction {

    /** Moderation cleared a campaign for launch. */
    PROJECT_APPROVED("project.approved", "project"),

    /** Moderation refused a campaign. Terminal for the campaign. */
    PROJECT_REJECTED("project.rejected", "project"),

    /** Moderation sent a campaign back to its creator. */
    PROJECT_CHANGES_REQUESTED("project.changes_requested", "project"),

    /**
     * An update was published to a campaign's backers (#83).
     *
     * <p>The entity is the campaign rather than the update, unlike
     * {@link #COLLABORATOR_INVITED}: an update has no later history of its own — §10.2
     * gives it no edit and no withdrawal — so a row keyed on it would be a history of
     * one, invisible to the one query anybody runs against this table. Which update is
     * in the detail, as its number.
     *
     * <p>Privileged because §5.5 makes publishing an update an obligation a funded
     * creator owes their backers, and because the statement is irreversible: everybody
     * following the campaign is told, and no endpoint takes it back.
     */
    PROJECT_UPDATE_PUBLISHED("project.update_published", "project"),

    /**
     * Somebody's comment was removed by somebody who did not write it (#84). §4.7's
     * CD-14 and AD-09.
     *
     * <p>The entity is the campaign rather than the comment, for
     * {@link #PROJECT_UPDATE_PUBLISHED}'s reason: a comment is removed once and never
     * restored, so a row keyed on it would be a history of one and invisible to the
     * question anybody actually asks — "what has this campaign's team been taking
     * down". Which comment, and whose, is in the detail.
     *
     * <p><strong>An author withdrawing their own comment is deliberately not recorded,
     * and that is the line.</strong> Deleting what you wrote is the ordinary use of a
     * button; deleting what somebody else wrote from a public page is one account
     * silencing another, is irreversible through any endpoint, and is the first thing a
     * complaint about over-moderation asks about. Recording both would bury the second
     * under the first at a ratio nobody can filter.
     */
    PROJECT_COMMENT_REMOVED("project.comment_removed", "project"),

    /**
     * Somebody was invited onto a campaign.
     *
     * <p>The entity is the grant and not the campaign, because the grant is what is
     * later widened and withdrawn, and three rows about one collaborator are only
     * a history if they share an identifier.
     */
    COLLABORATOR_INVITED("collaborator.invited", "collaborator"),

    /** A collaborator's capabilities were replaced. */
    COLLABORATOR_CAPABILITIES_CHANGED("collaborator.capabilities_changed", "collaborator"),

    /** A grant, or an invitation nobody had accepted, was withdrawn. */
    COLLABORATOR_REVOKED("collaborator.revoked", "collaborator"),

    /** An account was closed, starting §17.4's thirty-day grace period. */
    ACCOUNT_DELETION_REQUESTED("account.deletion_requested", "account"),

    /** A pending deletion was withdrawn inside the grace period. */
    ACCOUNT_DELETION_CANCELLED("account.deletion_cancelled", "account"),

    /**
     * Everything the platform holds about an account left it in one response.
     *
     * <p>Privileged despite changing nothing. §17.4's export endpoint is the single
     * most valuable request a stolen access token can make, and "when was this
     * account copied, and from where" is a question only this row can answer.
     */
    ACCOUNT_EXPORTED("account.exported", "account"),

    /** A second factor was confirmed and is now required to sign in. */
    TWO_FACTOR_ENABLED("two_factor.enabled", "account"),

    /** A second factor was removed, or somebody tried to remove one. */
    TWO_FACTOR_DISABLED("two_factor.disabled", "account"),

    /** One session was revoked, by its owner or by the platform. */
    SESSION_REVOKED("session.revoked", "session"),

    /** Every live session an account had was revoked at once. */
    SESSIONS_REVOKED("session.revoked_all", "account"),

    // ------------------------------------------------------------------
    // AD-02's report queue (#102). The two outcomes a moderator can choose.
    //
    // The entity is the report and not what was reported, for the reason
    // COLLABORATOR_INVITED gives about grants: the report is the thing that is
    // decided, and two rows about one report are only a history if they share an
    // identifier. Which campaign or account it was about is in `detail`, and on
    // the `content_reports` row this identifier names.
    //
    // Both outcomes are recorded, not only the one that agreed with the reporter.
    // "Who dismissed the fourteen reports about this campaign" is the question an
    // investigation starts from, and a table of upheld reports cannot answer it.
    // ------------------------------------------------------------------

    /** Moderation agreed with a report. Does not itself suspend or remove anything. */
    REPORT_UPHELD("report.upheld", "report"),

    /** Moderation did not agree with a report. */
    REPORT_DISMISSED("report.dismissed", "report");

    private final String action;
    private final String entityType;

    AuditAction(String action, String entityType) {
        this.action = action;
        this.entityType = entityType;
    }

    /** What goes in {@code audit_logs.action}. */
    public String action() {
        return action;
    }

    /** What goes in {@code audit_logs.entity_type}. */
    public String entityType() {
        return entityType;
    }
}
