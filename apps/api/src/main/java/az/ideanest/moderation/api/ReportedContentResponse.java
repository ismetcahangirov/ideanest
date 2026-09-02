package az.ideanest.moderation.api;

import az.ideanest.moderation.application.ReportedItem;
import az.ideanest.shared.project.ProjectSummary;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.UUID;

/**
 * What a report is about, on the wire — {@code GET /v1/admin/moderation/reports/{id}/content}.
 *
 * <p>Nulls are omitted, as on the queue's own responses: this feeds a screen rather than a
 * form, so "absent" and "empty" say the same thing to its reader. What a client must not
 * infer from an absent field is why it is absent — that is {@link #state}, which is present
 * on every answer and is the only thing worth branching on.
 *
 * @param state {@code PRESENT}, {@code REMOVED}, {@code GONE} or {@code ADDRESSED_DIRECTLY}
 *     — see {@link ReportedItem.State}, which argues each of the four
 * @param number the update's per-campaign sequence. Absent for everything else, rather than
 *     zero: "update 0" is not a thing anybody would render
 * @param project the campaign the content is on, with both halves of its public path so
 *     that a client can address it. Absent for an account
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReportedContentResponse(
        String targetType,
        String state,
        String title,
        String body,
        UUID authorId,
        Integer number,
        ReportedCampaign project,
        Instant createdAt) {

    /**
     * The campaign, as much of it as a link needs.
     *
     * <p>{@code creatorId} is deliberately not carried through from {@link ProjectSummary},
     * which documents it as "never rendered": it is there to be joined on inside the
     * service, and a console that had it would eventually print it.
     *
     * <p><strong>Not called {@code Campaign}</strong>, which is what it was for one export.
     * springdoc names a schema after the simple class name, and {@code BackerPledgeSummary}
     * already publishes a nested {@code Campaign}; the second registration is dropped rather
     * than refused, so {@code openapi.json} described this field with the backer summary's
     * seven fields and nothing failed. A nested record on a response record is a global name
     * in the specification, and the specification is what the client is generated from.
     */
    public record ReportedCampaign(UUID id, String title, String slug, String creatorSlug) {
    }

    public static ReportedContentResponse of(ReportedItem item) {
        return new ReportedContentResponse(
                item.targetType().name(),
                item.state().name(),
                item.title(),
                item.body(),
                item.authorId(),
                item.number() == 0 ? null : item.number(),
                campaign(item.project()),
                item.createdAt());
    }

    private static ReportedCampaign campaign(ProjectSummary summary) {
        return summary == null
                ? null
                : new ReportedCampaign(summary.id(), summary.title(), summary.slug(), summary.creatorSlug());
    }
}
