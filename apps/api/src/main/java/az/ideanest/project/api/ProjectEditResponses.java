package az.ideanest.project.api;

import az.ideanest.project.domain.Project;
import az.ideanest.shared.Money;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The one place a campaign becomes a {@link ProjectEdit}.
 *
 * <p>Nine endpoints across two controllers return this response. Mapped in one
 * place so that a field added to the projection appears in all nine, rather than
 * in the ones somebody remembered — the failure that makes a client's state depend
 * on which request it happened to make last.
 *
 * <p>It is also where #36 fills in {@code lockedFields}: one method, one list, and
 * no endpoint to revisit.
 */
@Component
public class ProjectEditResponses {

    private final ObjectMapper json;

    public ProjectEditResponses(ObjectMapper json) {
        this.json = json;
    }

    public ProjectEdit of(Project project) {
        return new ProjectEdit(
                project.getId(),
                project.getSlug(),
                project.getState().name(),
                project.getTitle(),
                project.getBlurb(),
                project.getCategoryId(),
                project.getSubcategoryId(),
                Money.orNull(project.getGoalAmount(), project.getCurrency()),
                project.getDurationDays(),
                project.getScheduledLaunchAt(),
                project.getLaunchedAt(),
                project.getDeadline(),
                storyOf(project),
                project.getRisks(),
                CoverImageBody.of(project.getCoverImage()),
                project.isLatePledgeEnabled(),
                // Empty, and populated by #36. See ProjectEdit for why it is here
                // before it is filled in.
                List.of(),
                project.getCreatedAt(),
                project.getUpdatedAt());
    }

    /**
     * The story document as JSON, not as a string containing JSON.
     *
     * <p>The column is {@code jsonb} and the entity holds it as text, so this is a
     * parse rather than a conversion. Returning the text would put a quoted,
     * escaped document in the response and make every client parse it a second
     * time — and the first client to forget would render the escapes.
     */
    private JsonNode storyOf(Project project) {
        String story = project.getStory();
        if (story == null) {
            return null;
        }
        try {
            return json.readTree(story);
        } catch (JsonProcessingException e) {
            // Unreachable: PostgreSQL validates jsonb on the way in, so a row that
            // fails here has been written by something that bypassed the column
            // type. Serving it as though it were fine would spread the problem.
            throw new IllegalStateException("Project " + project.getId() + " holds a story that is not JSON", e);
        }
    }
}
