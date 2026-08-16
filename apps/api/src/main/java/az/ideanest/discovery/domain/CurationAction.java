package az.ideanest.discovery.domain;

/**
 * What a curator did, as {@code curation_events.action} records it.
 *
 * <p>The vocabulary is repeated here and in V14's CHECK constraint rather than
 * shared, for the reason V6 gives about {@code projects_state_known}: an action added
 * later has to be a deliberate decision in both places, and a row claiming an action
 * that does not exist is worth refusing at the point it would be written.
 *
 * <p><strong>Every one of these is a privileged action</strong> (§3.2, CLAUDE.md), so
 * there is no path through {@code CurationService} that changes a collection without
 * writing one of them.
 */
public enum CurationAction {

    /** A list came into existence. Recorded so that a collection's history is complete. */
    COLLECTION_CREATED(false, false),

    /** Its slug, kind, window, badge grant, imagery, order, or copy changed. */
    COLLECTION_UPDATED(false, false),

    /**
     * It became visible to the public.
     *
     * <p>The moment the platform's attention starts being directed at the campaigns
     * in it, which is why the note is required.
     */
    COLLECTION_PUBLISHED(false, true),

    /** It stopped being visible. Also requires a note: withdrawing a feature is a decision. */
    COLLECTION_UNPUBLISHED(false, true),

    /** A campaign was curated into the list, and badged if the list badges. */
    PROJECT_ADDED(true, true),

    /** A campaign was taken out, and un-badged with it. */
    PROJECT_REMOVED(true, true),

    /** The sequence changed. Names no campaign: it is a statement about the whole list. */
    PROJECTS_REORDERED(false, false);

    private final boolean namesAProject;
    private final boolean requiresANote;

    CurationAction(boolean namesAProject, boolean requiresANote) {
        this.namesAProject = namesAProject;
        this.requiresANote = requiresANote;
    }

    /**
     * Whether this action is about one campaign.
     *
     * <p>The Java half of {@code curation_events_scope_matches_the_action}: an event
     * that says a campaign was removed and cannot say which is not an audit row. Held
     * in both places because a constraint violation reaches the client as a 500 and a
     * checked precondition as a refusal naming the field.
     */
    public boolean namesAProject() {
        return namesAProject;
    }

    /**
     * Whether the caller has to say why.
     *
     * <p>True for the four actions that change what the public sees. §3.2's badge is
     * discretionary, and the note is the only place the reason for one survives — a
     * year later the row is all that is left of "why was this campaign on the front
     * page". The three that are not required are the ones whose "why" is visible in
     * what changed: creating an empty unpublished list, editing its copy, and moving
     * cards around inside a list that is already published.
     */
    public boolean requiresANote() {
        return requiresANote;
    }
}
