package az.ideanest.project.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

/**
 * One story version, with its document — contract §5.
 *
 * <p>The same fields as {@link StoryVersionSummary} plus the document, so a client
 * that already rendered a row from the list can render the preview from this
 * response without holding both.
 *
 * <p>{@code ALWAYS} inclusion for the same reason {@code ProjectEdit} declares it:
 * the service serialises with {@code non_null} by default, and a preview that
 * silently omitted a null field would be indistinguishable from one where the
 * field was not part of the response at all.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record StoryVersionDetail(
        int number, Instant createdAt, UUID authorId, int characters, JsonNode document) {
}
