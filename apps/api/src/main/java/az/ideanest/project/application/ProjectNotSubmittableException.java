package az.ideanest.project.application;

import az.ideanest.project.domain.ChecklistItem;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The edge to {@code SUBMITTED} is allowed, and §5.3 refuses the campaign.
 *
 * <p>Answered as 409 with {@code code: PROJECT_NOT_SUBMITTABLE} and every unmet
 * requirement in {@code meta}. A conflict rather than a bad request, for the same
 * reason a refused transition is one: the request is well formed — there is no
 * body to get wrong — and what refuses it is the state of the campaign, which was
 * a different state a moment ago and will be a different one again as soon as the
 * creator fills in what is missing.
 *
 * <p><strong>Every failing requirement, not the first.</strong> A refusal that
 * named one missing field at a time would have a creator submit four times to
 * learn about four, and each round trip is a moment they think they are finished.
 *
 * <p>This is the enforcement half of the checklist. {@code GET
 * /v1/projects/{id}/checklist} is advice a client may ignore, misread, or be an
 * old build of; this is the same rules, evaluated by the same class, on the write
 * that matters. "State transitions are enforced server-side and cannot be
 * bypassed" is true because of this method and not because of that endpoint.
 */
public class ProjectNotSubmittableException extends RuntimeException {

    private final transient List<ChecklistItem> unmet;

    public ProjectNotSubmittableException(List<ChecklistItem> unmet) {
        super("A campaign cannot be submitted until "
                + unmet.stream()
                        .map(item -> item.requirement().name())
                        .collect(Collectors.joining(", ")) + " are satisfied");
        this.unmet = List.copyOf(unmet);
    }

    /**
     * The blocking requirements the campaign does not meet.
     *
     * <p>Carried as checklist items rather than as field names so that the problem
     * detail can say which editor section fixes each one — the same routing
     * information the checklist endpoint gives, so a client refused here can point
     * at exactly the controls it would have pointed at anyway.
     */
    public List<ChecklistItem> unmet() {
        return unmet;
    }
}
