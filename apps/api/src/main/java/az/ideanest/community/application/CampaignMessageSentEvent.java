package az.ideanest.community.application;

import az.ideanest.community.domain.CampaignMessage;
import java.time.Instant;
import java.util.UUID;

/**
 * {@code project.message_sent}: §4.7's CD-13, announced through §8.3's outbox.
 *
 * <p>Recorded by {@link CampaignMessageService} in the same transaction as the
 * {@code campaign_messages} row, so the record of the message and the instruction to deliver it
 * are one fact. The failure the ordering prevents is the interesting one: a message row with no
 * event is a creator shown a sent message that reached nobody, and an event with no row is a
 * delivery nothing can account for afterwards.
 *
 * <h2>Why the audience is not in here</h2>
 *
 * <p>{@code Outbox} asks for enough to route on and no more, and an audience of five thousand
 * identifiers in a payload is the shape that argument exists to refuse. So the event carries the
 * <em>segment</em> and the notification module asks {@code shared.audience.SegmentAudience} at
 * translation time — the same arrangement #245 arrived at for a campaign's backers.
 *
 * <p><strong>Which means the audience is resolved twice</strong>, once here to freeze
 * {@code recipient_count} and once in the listener to write the rows, and the two can differ if
 * somebody pledges in between. That is not a defect to be engineered away: the count is what the
 * creator was told they were sending to, and the delivery is who the campaign's backers actually
 * are when it goes. Freezing an audience list to make them agree would be storing a membership
 * list, which V31 refuses at length.
 *
 * <h2>Why the body is in here</h2>
 *
 * <p>Unlike a campaign's title, which {@code shared.project.ProjectSummaries} publishes as a
 * question. The difference is that a title is a fact about a long-lived row that any module may
 * ask about, and a message body exists only for this delivery — a port for it would be a
 * published read of one module's content by another, used once. It is bounded at 2,000
 * characters, which {@code V34} argues is a product decision rather than a technical bound.
 *
 * <p>This is a copy of the contract in the sense {@code CampaignFinalisedEvent} is: the
 * notification module declares its own reading of the same JSON, neither imports the other, and
 * the field names below are therefore the contract.
 *
 * @param messageId the message, which is also the notification's subject — so a reader who
 *     received one can be shown it again from their inbox
 * @param projectId which campaign. Also the aggregate identifier
 * @param segmentId the saved segment, or <strong>null for every backer of the campaign</strong>.
 *     The listener switches on exactly this
 * @param sentBy who sent it. Not rendered — a message is from the campaign, not from a
 *     collaborator's personal account — and carried so that a support question about a message
 *     can be answered from the event alone
 * @param subject the message's subject line
 * @param body its text
 * @param sentAt when it was sent. Not when it is delivered
 */
public record CampaignMessageSentEvent(
        UUID messageId,
        UUID projectId,
        UUID segmentId,
        UUID sentBy,
        String subject,
        String body,
        Instant sentAt) {

    /** §7.2's aggregate name, shared with every other event about a campaign. */
    public static final String AGGREGATE_TYPE = "project";

    public static final String EVENT_TYPE = "project.message_sent";

    static CampaignMessageSentEvent of(CampaignMessage message, Instant sentAt) {
        return new CampaignMessageSentEvent(
                message.getId(),
                message.getProjectId(),
                message.getSegmentId(),
                message.getSentBy(),
                message.getSubject(),
                message.getBody(),
                sentAt);
    }
}
