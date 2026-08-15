package az.ideanest.project.api;

import az.ideanest.project.application.ProjectFieldRejectedException;
import az.ideanest.project.application.ProjectPatch;
import az.ideanest.shared.Money;
import az.ideanest.shared.Patched;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

/**
 * A partial edit of a campaign, with JSON Merge-Patch semantics (RFC 7396).
 *
 * <p>Every field is a {@link Patched}: a field the client did not mention is left
 * alone and a field explicitly set to {@code null} is cleared. The campaign editor
 * autosaves one field at a time, so a body containing only a title is the normal
 * case — and reading that as "set the title, clear everything else" would delete a
 * creator's work in a request that looks entirely ordinary in a log.
 *
 * <p>Bean validation annotations are deliberately absent. They cannot see inside a
 * {@code Patched}, and a rule enforced here as well as in
 * {@code ProjectEditingService} would be two rules that can disagree — with the
 * service's version being the one that also holds for every other caller. The
 * lengths and ranges of §5.3 are checked there, and in the database beneath it.
 *
 * @param story the rich-text document, opaque here and validated by #35. Kept as a
 *     tree rather than a typed model so that adding that validation is a change to
 *     one class instead of to this record's shape
 */
public record ProjectPatchRequest(
        Patched<String> title,
        Patched<String> blurb,
        Patched<UUID> categoryId,
        Patched<UUID> subcategoryId,
        Patched<Money> goal,
        Patched<Integer> durationDays,
        Patched<Instant> scheduledLaunchAt,
        Patched<JsonNode> story,
        Patched<String> risks,
        Patched<CoverImageBody> coverImage,
        Patched<Boolean> latePledgeEnabled) {

    public ProjectPatchRequest {
        // Absence is the neutral value, so a null component becomes absent rather
        // than "clear this field". See Patched: Jackson's absent hook already does
        // this, and this is the belt to its braces.
        title = Patched.orAbsent(title);
        blurb = Patched.orAbsent(blurb);
        categoryId = Patched.orAbsent(categoryId);
        subcategoryId = Patched.orAbsent(subcategoryId);
        goal = Patched.orAbsent(goal);
        durationDays = Patched.orAbsent(durationDays);
        scheduledLaunchAt = Patched.orAbsent(scheduledLaunchAt);
        story = Patched.orAbsent(story);
        risks = Patched.orAbsent(risks);
        coverImage = Patched.orAbsent(coverImage);
        latePledgeEnabled = Patched.orAbsent(latePledgeEnabled);
    }

    /**
     * The same edit, in the shape the application layer works in.
     *
     * <p>Two conversions happen here and nowhere else: the cover image becomes the
     * value object that keeps its three columns together, and the story document
     * becomes the text stored in a {@code jsonb} column. Both preserve the
     * difference between "absent" and "cleared", which is what
     * {@link Patched#map} exists for.
     */
    public ProjectPatch toPatch() {
        return new ProjectPatch(
                title,
                blurb,
                categoryId,
                subcategoryId,
                goal,
                durationDays,
                scheduledLaunchAt,
                story.map(ProjectPatchRequest::asDocument),
                risks,
                coverImage.map(CoverImageBody::toDomain),
                latePledgeEnabled);
    }

    /**
     * The document as the text that goes into the column.
     *
     * <p>Required to be a JSON object rather than any JSON value. {@code jsonb}
     * would happily store {@code 5} or {@code "text"}, and a client reading the
     * story back expects the document of #35 — a scalar there would fail in the
     * editor rather than at the request that stored it.
     */
    private static String asDocument(JsonNode story) {
        if (!story.isObject()) {
            throw new ProjectFieldRejectedException("story", "A story is a document, not a single value.");
        }
        return story.toString();
    }
}
