package az.ideanest.community.api;

import az.ideanest.community.domain.CampaignMessage;
import java.time.Instant;
import java.util.UUID;

/**
 * One message a campaign sent.
 *
 * @param id the message
 * @param segmentId which saved segment it went to, or null for every backer
 * @param segmentName what that segment was called <strong>when the message went</strong>, or
 *     null with the identifier. A snapshot rather than a join, so a renamed or deleted segment
 *     does not rewrite the record — {@code V34} argues it
 * @param subject the subject line
 * @param body the message. Returned in full: this is the campaign's own outgoing post, read by
 *     somebody on its team, and a list of subject lines with no way to see what was said is a
 *     list nobody can use to answer "what did we tell them"
 * @param recipientCount how many people it reached, frozen at send time
 * @param truncated whether the audience hit the platform's ceiling. <strong>A field rather than
 *     a silence</strong>, for the reason CD-11's export header exists: a message that reached
 *     five thousand of six thousand backers looks exactly like one that reached everybody
 * @param sentAt when it went
 */
public record CampaignMessageResponse(
        UUID id,
        UUID segmentId,
        String segmentName,
        String subject,
        String body,
        int recipientCount,
        boolean truncated,
        Instant sentAt) {

    public static CampaignMessageResponse of(CampaignMessage message) {
        return new CampaignMessageResponse(
                message.getId(),
                message.getSegmentId(),
                message.getSegmentName(),
                message.getSubject(),
                message.getBody(),
                message.getRecipientCount(),
                message.isTruncated(),
                message.getCreatedAt());
    }
}
