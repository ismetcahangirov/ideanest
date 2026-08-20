package az.ideanest.pledge.api;

import az.ideanest.pledge.application.BackerFilter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * "Save this filter under this name", and "replace that one with this".
 *
 * <p>One request record for the create and the replace, because they take the same thing:
 * a segment is a name and a filter, and there is nothing a replace may change that a
 * create may not set. A separate patch shape would need a way to say "clear the country
 * filter" that is distinguishable from "leave it alone" — {@code Patched} exists in this
 * codebase for that problem, and a four-axis filter a creator re-picks in one interaction
 * does not have it.
 *
 * @param name what to call it. Eighty characters, matching V31's constraint; compared
 *     folded and trimmed, so a name differing only in case collides
 * @param filter which backers. Absent is {@link BackerFilter#ANY} — a segment that selects
 *     the whole campaign, which is a reasonable thing to save and name
 */
public record SaveBackerSegmentRequest(
        @NotBlank(message = "A segment has a name")
                @Size(max = 80, message = "A segment name may not exceed 80 characters")
                String name,
        @Valid BackerFilterBody filter) {

    /** The filter this request describes, defaulting to the whole campaign. */
    public BackerFilter toFilter() {
        return filter == null ? BackerFilter.ANY : filter.toFilter();
    }
}
