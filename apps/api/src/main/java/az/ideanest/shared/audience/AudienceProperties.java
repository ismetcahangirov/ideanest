package az.ideanest.shared.audience;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The bound on an audience the platform computes.
 *
 * <p><strong>This lived on {@code NotificationProperties} until #98, and moving it is the point
 * of this class rather than tidying.</strong> When the notification module was the only thing
 * that resolved an audience, the bound was genuinely its own — {@code ProjectAudiences} says as
 * much: "the bound is the caller's rather than the implementation's, because only the caller
 * knows what it can do with the answer".
 *
 * <p>#98 added a second caller with a different reason for asking. {@code CampaignMessageService}
 * resolves the audience when the message is sent, to freeze how many people it reached; the
 * notification module resolves it again at delivery, to write the rows. Those two numbers are
 * shown to the same creator as one fact — "your message reached 4,000 backers" — so two
 * independently configured ceilings would be a response that disagrees with the delivery, and the
 * disagreement would appear only on the campaigns large enough to hit either.
 *
 * <p>So the bound stops being a property of a caller and becomes a property of <em>asking</em>,
 * which is what this package is. {@code ideanest.notification.audience.max-recipients} is now
 * {@code ideanest.audience.max-recipients}; a deployment carrying the old key gets the default
 * and no warning, which is the honest cost of the rename and is worth stating rather than
 * papering over with an alias nobody removes.
 *
 * @param maxRecipients how many people one event may fan out to.
 *     <strong>A limit on one transaction, and the reason there is one at all is that the fan-out
 *     runs inside the outbox dispatch.</strong> "Goal reached" on a campaign with twenty thousand
 *     backers is sixty thousand rows written by the transaction that also marks the event
 *     delivered, plus an {@code IN} list of twenty thousand identifiers to check they are
 *     accounts — one transaction whose size is decided by how well a campaign did, holding locks
 *     for as long as it takes.
 *     <p><strong>Exceeding it is logged at {@code ERROR} and never silent.</strong> Both callers
 *     ask the audience port for one more than this and compare, so truncation is a fact they know
 *     rather than infer, and each says which campaign and how many people were not told. A
 *     message additionally reports it to the creator, in
 *     {@code campaign_messages.truncated}. A fan-out chunked across several transactions is what
 *     removes the bound rather than raising it, and it is not this change.
 *     <p>Five thousand: comfortably above every campaign the platform has, and small enough that
 *     the transaction above is one an operator would not notice. It is a ceiling rather than a
 *     target
 */
@ConfigurationProperties(prefix = "ideanest.audience")
public record AudienceProperties(int maxRecipients) {

    private static final int DEFAULT_MAX_RECIPIENTS = 5_000;

    public static AudienceProperties defaults() {
        return new AudienceProperties(DEFAULT_MAX_RECIPIENTS);
    }

    public AudienceProperties {
        // Binding leaves an omitted property at its zero value, so a deployment that configures
        // nothing gets the documented default rather than an audience of nobody.
        maxRecipients = maxRecipients == 0 ? DEFAULT_MAX_RECIPIENTS : maxRecipients;

        if (maxRecipients < 1) {
            throw new IllegalArgumentException("An event tells at least one person");
        }
    }
}
