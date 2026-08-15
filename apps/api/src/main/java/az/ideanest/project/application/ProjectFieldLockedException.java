package az.ideanest.project.application;

import az.ideanest.project.domain.LockedField;
import az.ideanest.project.domain.ProjectEditLocks;
import az.ideanest.project.domain.ProjectState;

/**
 * An edit touched a field §5.3 froze when the campaign launched.
 *
 * <p><strong>409, not 400</strong>, and that is the whole reason this is a separate
 * type from {@link ProjectFieldRejectedException}. A goal of {@code "-5.00"} is
 * malformed and will be malformed forever; a goal of {@code "6000.00"} is a
 * perfectly good number that would have been accepted an hour earlier and is
 * refused by the state the campaign is now in. That is the same shape as
 * {@link ProjectTransitionNotAllowedException}, which is already a 409 for exactly
 * that reason, and the difference matters to a client: a 400 says "fix the value",
 * a 409 says "reload — the campaign has moved on". The editor's autosave frequently
 * finds out this way that another tab, or a scheduled launch, went live underneath
 * it.
 *
 * <p>The field name travels as the JSON key the client sent, so the message can be
 * put beside the input rather than in a banner, and the state travels with it so
 * the client can explain <em>why</em> without a second request. The same pair is in
 * every editor response as {@code state} and {@code lockedFields}, which is what a
 * well-behaved client uses to disable the input before anybody presses anything;
 * this exception is what stands behind that for the client that does not, and for
 * every request that did not come from the editor at all.
 */
public class ProjectFieldLockedException extends RuntimeException {

    private final String field;
    private final ProjectState state;

    /**
     * @param field the frozen rule, which knows its own name on the wire. Taking the
     *     enum rather than a string means the refusal cannot name a field
     *     {@link ProjectEditLocks} has never heard of
     */
    public ProjectFieldLockedException(LockedField field, ProjectState state) {
        super("A campaign in " + state + " can no longer change its " + field.wireName() + ".");
        this.field = field.wireName();
        this.state = state;
    }

    public String field() {
        return field;
    }

    public ProjectState state() {
        return state;
    }
}
