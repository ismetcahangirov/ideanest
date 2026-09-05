package az.ideanest.obligation.application;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code obligation.update_due_soon}: a creator's month is nearly up — §5.5, issue #437.
 *
 * <p>Recorded through §8.3's outbox in the same transaction as the claim that authorises it, so
 * a reminder that rolled back is one nobody was sent — {@code DeadlineReminderSender} makes the
 * identical argument about a deadline notice, and the failure it prevents is the same: a claim
 * with no event is a creator who is never warned, permanently, and an event with no claim is a
 * message every morning.
 *
 * <h2>Why there is no matching lapse event</h2>
 *
 * <p>A lapse notifies nobody. #437: "the point is compliance, not catching people" — so the
 * warning goes out before the month is up, and what a lapse produces is a visible state on the
 * campaign page and a row in a moderator's queue. Announcing a lapse to a creator who has just
 * been warned would be the platform saying the same thing twice with a worse tone, and
 * announcing it to backers would be the platform publishing a verdict about a dispute it only
 * mediates.
 *
 * <p>{@code project} is the aggregate, for {@code CampaignFinalisedEvent}'s reason: a campaign's
 * events reach a consumer in the order they were written, so "an update was published" cannot
 * overtake "an update is due".
 *
 * @param projectId which campaign. Also the aggregate identifier
 * @param creatorId who is being reminded — the one recipient, and the one nobody else can look
 *     up without reading {@code projects}
 * @param dueAt when the update is owed. Carried so the message can say a date rather than "soon"
 * @param lastUpdateAt when they last posted, or null if they never have since the campaign
 *     closed. The difference the message leads with
 */
public record UpdateDueSoonEvent(UUID projectId, UUID creatorId, Instant dueAt, Instant lastUpdateAt) {

    /** §7.2's aggregate name, shared with every other event about a campaign. */
    public static final String AGGREGATE_TYPE = "project";

    public static final String EVENT_TYPE = "obligation.update_due_soon";
}
