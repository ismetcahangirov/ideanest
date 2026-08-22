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

    /**
     * A campaign's backer report left the platform as a file (#79). §4.7's CD-11.
     *
     * <p>Privileged despite changing nothing, for {@link #ACCOUNT_EXPORTED}'s reason and
     * with the subject the other way round: one request copies out the name and email
     * address of <em>every person who backed the campaign</em>. It is the most valuable
     * request a stolen collaborator token can make on the creator dashboard, and "who
     * exported this campaign's backers, when, and from where" is a question only this row
     * can answer.
     *
     * <p>The entity is the campaign rather than the export, because there is no export
     * object: §10.2 answers the route with a file rather than with a job somebody later
     * fetches. How many rows left, and under which filter, is in the detail.
     */
    PROJECT_BACKERS_EXPORTED("project.backers_exported", "project"),

    /**
     * A message was sent to a campaign's backers, or to a saved segment of them — §4.7's CD-13
     * (#98).
     *
     * <p>Audited for the reason the export above is, and for one of its own. The export's is
     * that it moves personal data out of the platform; this one's is that it puts a message
     * <em>into</em> several thousand inboxes in the campaign's name, and it cannot be taken
     * back. "Who sent this, when, to which segment, and how many people got it" is a question
     * only this row can answer once the segment has been renamed or deleted.
     *
     * <p>The entity is the campaign rather than the message, following the export: a caller
     * asking "what has happened to this campaign" should find it, and the message identifier is
     * in the detail.
     *
     * <p><strong>The detail carries the act and never the content.</strong> This table has no
     * retention rule and refuses {@code DELETE}, so a creator's prose in it is a decision nobody
     * can reverse. {@code campaign_messages} is where the text lives.
     */
    PROJECT_SEGMENT_MESSAGED("project.segment_messaged", "project"),

    /**
     * §4.8's PM-08 (#75): a creator froze their campaign's shipping addresses.
     *
     * <p>Audited although the backer's own write of an address is not, and the asymmetry is
     * the rule rather than an omission. A backer editing their own address is somebody
     * changing their own data; a lock is a privileged action taken over several thousand
     * other people's, by somebody who is not any of them, and after it they cannot correct a
     * mistake without asking. "Who stopped them, and when" is precisely what an append-only
     * table is for.
     *
     * <p>The entity is the campaign, following {@link #PROJECT_SEGMENT_MESSAGED}: the
     * question somebody asks is "what has happened to this campaign".
     *
     * <p><strong>The detail carries counts and no address.</strong> This table has no
     * retention rule and refuses {@code DELETE}, so a postal address in it would outlive
     * §17.4's erasure — which is the one thing V36's encryption cannot protect against.
     */
    PROJECT_ADDRESSES_LOCKED("project.addresses_locked", "project"),

    /**
     * §4.8's PM-04 (#74): a survey went out to a campaign's backers.
     *
     * <p>Audited for {@link #PROJECT_SEGMENT_MESSAGED}'s reason and one of its own: it is a
     * message several thousand people receive in the campaign's name and it cannot be taken
     * back, and it is the moment the survey's questions freeze. "Who sent it, when, and how
     * many people did it reach" is the first question after a survey asks the wrong thing.
     *
     * <p>The detail carries the counts and never a question. {@code surveys} is where the text
     * lives, and it can be deleted; this table cannot.
     */
    PROJECT_SURVEY_SENT("project.survey_sent", "project"),

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
