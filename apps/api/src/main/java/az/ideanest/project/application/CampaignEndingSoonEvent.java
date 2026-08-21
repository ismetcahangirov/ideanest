package az.ideanest.project.application;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code project.ending_soon}: a live campaign has crossed one of §4.10's deadline thresholds.
 *
 * <p>Recorded by {@link DeadlineReminderSender} through §8.3's outbox, in the same transaction
 * as the {@code deadline_notices} row that claims the threshold. That is what makes the
 * announcement exactly-once in the only sense available: the claim and the event commit
 * together, so a crash either leaves the threshold unclaimed and unannounced, or claimed and
 * announced.
 *
 * <h2>One event type with a threshold, not two event types</h2>
 *
 * <p>The opposite of the decision {@code CampaignFinalisedEvent} makes, and deliberately.
 * There, the outcome is the event type because two outcomes mean two different messages to the
 * same people and consumers genuinely route on the difference. Here the two thresholds mean the
 * <em>same</em> message with a different number in it, sent to the same audience, and every
 * consumer would have two identical branches. Where the difference does matter — §4.10 gives
 * "48 hours remaining" an email column and "24 hours remaining" none — it is a property of the
 * notification type, which is where that table already lives.
 *
 * <p>The rule the two cases share: the event type carries what consumers route on, and nothing
 * else. It happens to be the outcome there and the campaign here.
 *
 * <h2>What travels</h2>
 *
 * <p>{@code Outbox} asks for enough to route on and no more; the notification module cannot read
 * {@code projects}, so the creator and the deadline come with it for
 * {@code CampaignFinalisedEvent}'s reason. The title does not — that is
 * {@code shared.project.ProjectSummaries} at translation time.
 *
 * <p>No pledged total and no goal, which "48 hours remaining" might have been expected to
 * carry. Two reasons: the number is still moving, so a copy of it in an event is a number that
 * is wrong by the time the message is read, and a deadline notice that reported a campaign was
 * short of its goal would be a message telling backers their money is probably not going to be
 * taken — which §5.1 has not decided yet.
 *
 * @param projectId which campaign. Also the aggregate identifier
 * @param creatorId whose campaign it is
 * @param hoursRemaining the threshold that was crossed: 48 or 24. <strong>The threshold, not a
 *     computed remainder</strong> — it is the constant the sweep claimed, so a redelivery six
 *     hours later still says 48 rather than reporting how long is left now, and the message a
 *     backer reads matches the one the platform decided to send
 * @param endsAt when the campaign closes, which is the fact a reader can act on
 * @param crossedAt when the sweep claimed the threshold
 */
public record CampaignEndingSoonEvent(
        UUID projectId, UUID creatorId, int hoursRemaining, Instant endsAt, Instant crossedAt) {

    /** §7.2's aggregate name, shared with every other event about a campaign. */
    public static final String AGGREGATE_TYPE = "project";

    public static final String EVENT_TYPE = "project.ending_soon";
}
