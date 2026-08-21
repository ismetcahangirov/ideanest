package az.ideanest.community.api;

import az.ideanest.community.domain.CampaignMessage;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * What a creator sends to their backers — §4.7's CD-13.
 *
 * @param segmentId the saved segment to send to, or <strong>absent for every backer</strong>.
 *     The two are different acts and not a convenience: messaging everybody needs only
 *     {@code PUBLISH_UPDATES}, while choosing a segment additionally needs
 *     {@code VIEW_FINANCES}, because a segment selects people by what the backer report knows
 *     about them. {@code CampaignMessageService} argues it
 * @param subject the subject line. Bounded here as well as on the entity and in the schema —
 *     the bean validation is what gives a client a field-level error before anything is read
 * @param body the message. Short on purpose: {@code V34} argues that long-form belongs in a
 *     project update, which stores the text once and serves it from a page, where a message is
 *     copied into the rendering document of every notification it produces
 */
public record SendMessageRequest(
        UUID segmentId,
        @NotBlank @Size(max = CampaignMessage.MAX_SUBJECT) String subject,
        @NotBlank @Size(max = CampaignMessage.MAX_BODY) String body) {
}
