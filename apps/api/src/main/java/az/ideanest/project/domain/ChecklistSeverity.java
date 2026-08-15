package az.ideanest.project.domain;

/**
 * Whether a checklist requirement stops a submission or only weakens it.
 *
 * <p><strong>The distinction is the whole reason the checklist is not a
 * boolean.</strong> §5.3 is a list of rules a campaign must satisfy before it may
 * be submitted, and a screen that showed only those would answer one question —
 * "can I press submit yet" — which the submit button already answers. What a
 * creator also needs is the second list: the things that are permitted and are a
 * bad idea. A campaign with no rewards is legal (§5.3 allows zero tiers) and
 * raises a fraction of what the same campaign raises with three.
 *
 * <p>The two are kept apart in the response as well as here, so that a client
 * cannot render a suggestion as a barrier. That failure is not hypothetical: an
 * interface that lists "add a reward tier" beside "a cover image is required",
 * both in red, teaches creators that the checklist exaggerates — and the first
 * thing they stop reading is the half that was true.
 */
public enum ChecklistSeverity {

    /** §5.3 refuses the submission until this is satisfied. */
    BLOCKING,

    /** Permitted, and worse. Never refuses anything. */
    ADVISORY
}
