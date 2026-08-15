package az.ideanest.project.api;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of the story's version list — contract §5.
 *
 * <p><strong>Without the document.</strong> Fifty versions of a persuasive
 * campaign story is a response measured in megabytes, and a list is opened to
 * choose one rather than to read all of them. The document is fetched by
 * {@code GET .../story/versions/{number}} when the creator asks to see it.
 *
 * @param characters how much prose the version holds, counted as §5.3 counts it
 *     (see {@code StoryDocuments#characterCount}). It is the one number that makes
 *     a list of timestamps usable: "3 minutes ago, 1,240 characters" tells a
 *     creator which of two versions came before they deleted a section, and a
 *     timestamp alone does not
 */
public record StoryVersionSummary(int number, Instant createdAt, UUID authorId, int characters) {
}
