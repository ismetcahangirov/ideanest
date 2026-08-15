package az.ideanest.project.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * What a client sends to open a draft.
 *
 * <p>A title and nothing else. §4.6 lists a dozen fields the editor collects, and
 * demanding any of them here would mean a creator filling in a form before they
 * could reach the form. Everything else arrives through {@code PATCH}, and the
 * completeness checklist (#37) is what decides when there is enough of it to
 * submit.
 *
 * @param title 1–60 characters (§5.3). Checked here so the message names the field,
 *     and again in the service and the database, which are the checks that hold
 *     against callers that are not this endpoint
 */
public record CreateProjectRequest(
        @NotBlank(message = "A title is required")
                @Size(max = 60, message = "A title may not exceed 60 characters")
                String title) {
}
