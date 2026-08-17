package az.ideanest.project.api;

import az.ideanest.project.domain.StoryDocuments;
import az.ideanest.project.domain.StoryVersion;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The one place a {@link StoryVersion} becomes a response.
 *
 * <p>Both endpoints that return one go through here, for the reason
 * {@link ProjectEditResponses} gives: the character count is derived rather than
 * stored, and two derivations of it would eventually disagree about whether a
 * version has enough prose to submit.
 */
@Component
public class StoryVersionResponses {

    private final ObjectMapper json;

    public StoryVersionResponses(ObjectMapper json) {
        this.json = json;
    }

    public List<StoryVersionSummary> summaries(List<StoryVersion> versions) {
        return versions.stream().map(this::summaryOf).toList();
    }

    public StoryVersionSummary summaryOf(StoryVersion version) {
        return new StoryVersionSummary(
                version.getVersionNumber(),
                version.getCreatedAt(),
                version.getAuthorId(),
                StoryDocuments.characterCount(documentOf(version)));
    }

    public StoryVersionDetail detailOf(StoryVersion version) {
        JsonNode document = documentOf(version);
        return new StoryVersionDetail(
                version.getVersionNumber(),
                version.getCreatedAt(),
                version.getAuthorId(),
                StoryDocuments.characterCount(document),
                document);
    }

    /**
     * The stored document as JSON, not as a string containing JSON.
     *
     * <p>The column is {@code jsonb} and the entity holds it as text, so this is a
     * parse rather than a conversion. Returning the text would put a quoted,
     * escaped document in the response and make every client parse it twice — and
     * the first client to forget would render the escapes.
     */
    private JsonNode documentOf(StoryVersion version) {
        try {
            return json.readTree(version.getDocument());
        } catch (JacksonException e) {
            // Unreachable: PostgreSQL validates jsonb on the way in, so a row that
            // fails here was written by something that bypassed the column type.
            // Serving it as though it were fine would spread the problem.
            throw new IllegalStateException("Story version " + version.getId() + " is not JSON", e);
        }
    }
}
