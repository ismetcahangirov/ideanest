package az.ideanest.project.api;

import java.util.UUID;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The creator's story document, as JSON rather than as a string containing JSON.
 *
 * <p>The column is {@code jsonb}, {@code PublicProjectPage} holds it as text — a
 * projection that parsed it would be a second implementation of #35's schema in the read
 * path — and returning the text would hand every client an escaped document to parse a
 * second time. So somebody has to parse it on the way out, and the only question is who.
 *
 * <p><strong>Not the response record's factory.</strong> {@code ProjectPageResponse.of}
 * takes the parsed node for that reason, and {@code ProjectEditResponses} makes the same
 * split: a record's factory holding an {@link ObjectMapper} is a record that cannot be
 * constructed in a test without one.
 *
 * <p><strong>Here rather than on a controller, since #399.</strong> Two endpoints now
 * serve the same page — the public one at {@code /v1/projects/{creatorSlug}/{projectSlug}}
 * and the console's staff preview at {@code /v1/admin/projects/{id}} — and the second was
 * added precisely so that a moderator reads what a backer would. A private copy of this
 * method on each controller is two copies of "what happens when the document does not
 * parse", and the answer to that has to be the same on both.
 */
final class StoryJson {

    private StoryJson() {
    }

    /**
     * @param story the {@code jsonb} column as text, or null on a campaign with no story
     * @param projectId named in the failure, because the exception below is one somebody
     *     has to go and look at a row about
     * @return the parsed document, or null
     */
    static JsonNode of(ObjectMapper json, UUID projectId, String story) {
        if (story == null) {
            return null;
        }
        try {
            return json.readTree(story);
        } catch (JacksonException e) {
            // Unreachable: PostgreSQL validates jsonb on the way in, so a row that fails
            // here was written by something that bypassed the column type. Serving it as
            // though it were fine would spread the problem onto a public page.
            throw new IllegalStateException("Project " + projectId + " holds a story that is not JSON", e);
        }
    }
}
