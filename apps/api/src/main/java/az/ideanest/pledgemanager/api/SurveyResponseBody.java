package az.ideanest.pledgemanager.api;

import az.ideanest.pledgemanager.application.SurveyDetail;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A survey as its creator's builder sees it.
 *
 * <p>The response of every creator-facing survey endpoint — create, read, update, send
 * and list — so a client applies the same update to its state whichever call it made.
 *
 * @param sent whether it has gone out. A boolean beside the instant for
 *     {@code ShippingAddressResponse}'s reason: a client disables the builder from the
 *     first and renders a sentence from the second
 * @param sentTo how many backers it reached, frozen at the send. Null on a draft
 * @param responseCount how many have answered, which is the number a creator watches
 *     against {@code sentTo}
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record SurveyResponseBody(
        UUID id,
        UUID projectId,
        String title,
        String message,
        Instant respondBy,
        boolean sent,
        Instant sentAt,
        Integer sentTo,
        long responseCount,
        List<SurveyQuestionBody> questions,
        Instant createdAt,
        Instant updatedAt) {

    public static SurveyResponseBody of(SurveyDetail detail) {
        return new SurveyResponseBody(
                detail.survey().getId(),
                detail.survey().getProjectId(),
                detail.survey().getTitle(),
                detail.survey().getMessage(),
                detail.survey().getRespondBy(),
                detail.survey().isSent(),
                detail.survey().getSentAt(),
                detail.survey().getSentTo(),
                detail.responseCount(),
                detail.questions().stream().map(SurveyQuestionBody::of).toList(),
                detail.survey().getCreatedAt(),
                detail.survey().getUpdatedAt());
    }
}
