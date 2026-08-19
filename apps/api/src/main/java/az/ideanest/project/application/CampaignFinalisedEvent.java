package az.ideanest.project.application;

import az.ideanest.project.domain.CampaignOutcome;
import az.ideanest.project.domain.Project;
import az.ideanest.shared.money.Money;
import java.time.Instant;
import java.util.UUID;

/**
 * {@code project.succeeded} and {@code project.unsuccessful}: §5.1 decided, announced.
 *
 * <p>Recorded by {@link CampaignFinalizer} through §8.3's outbox, inside the transaction
 * that performs the {@code LIVE → SUCCESSFUL} or {@code LIVE → UNSUCCESSFUL} transition
 * and writes V29's frozen columns. All three commit together or none of them does, which
 * is the only ordering in which "we told everybody the campaign succeeded" cannot be true
 * of a campaign that did not.
 *
 * <h2>The outcome is the event type, not a field</h2>
 *
 * <p>One record and two names. The alternative — one {@code project.finalised} carrying
 * an {@code outcome} — was rejected because every consumer would then switch on a string
 * to discover it had this module's event and switch on a field to discover what it said,
 * and the two could disagree. §4.10 gives the outcomes two different notification rows
 * with two different bodies going to the same people, so the routing decision is real and
 * belongs in the place consumers already route on.
 *
 * <p>The cost is that a consumer wanting both has two cases. That is the correct cost:
 * "tell me when a campaign closes, either way" is not something any surface in §4.10
 * actually wants.
 *
 * <h2>Why the numbers travel</h2>
 *
 * <p>{@code Outbox}'s guidance is "enough to route on, and no more", and its assumption is
 * a consumer that can read the rest inside its own transaction. The notification module
 * cannot: {@code projects} is this module's table and reaching into it is what
 * {@code ModuleBoundaryTests} forbids. §4.10's "campaign succeeded" message says what the
 * campaign raised, so the amount has to be here or the message cannot be written.
 *
 * <p>They are the <em>frozen</em> numbers — {@link Project#getOutcomePledgedAmount()},
 * not {@link Project#getPledgedAmount()} — and V29's header is the argument for the
 * difference. A redelivery of this event eight hours later, after two cards have been
 * refused, must produce the same message it would have produced at the deadline.
 *
 * <p>This is a copy of the contract in the same sense {@code PledgeConfirmedEvent} is:
 * {@code NotificationEvents} declares its own reading of the same JSON, neither imports
 * the other, and the field names below are therefore the contract. Renaming one breaks
 * every consumer without breaking any compilation, which is why
 * {@code CampaignFinalisedEventTests} asserts the names literally.
 *
 * @param projectId which campaign. Also the aggregate identifier, so §8.3's ordering is
 *     per campaign — a campaign cannot be announced as unsuccessful before it is
 *     announced as having reached its goal
 * @param creatorId whose campaign it is. The one recipient every consumer wants and the
 *     one nobody else can look up without reading {@code projects}
 * @param goal what it had to raise, as §10.3's {@code {"amount", "currency"}} object with
 *     a string amount. <strong>Never a JSON number</strong>
 * @param pledged what it raised, frozen at the deadline
 * @param backersCount how many people were behind that total
 * @param finalisedAt when §5.1 was applied. The instant on the row, read back rather than
 *     taken from the clock again, so the event and {@code projects.finalized_at} cannot
 *     come to two answers about when the campaign closed
 */
public record CampaignFinalisedEvent(
        UUID projectId, UUID creatorId, Money goal, Money pledged, int backersCount, Instant finalisedAt) {

    /**
     * Which kind of thing this happened to, and half of §8.3's ordering key.
     *
     * <p>The same word {@code project.approved} and {@code project.goal_reached} are
     * recorded under, deliberately: a second aggregate name for the same rows would give
     * a campaign two independent orderings, and "goal reached" arriving after "campaign
     * succeeded" is exactly the reordering that would confuse a backer.
     */
    public static final String AGGREGATE_TYPE = "project";

    /** §5.1's first branch: the total reached the goal by the deadline. */
    public static final String SUCCEEDED = "project.succeeded";

    /** §5.1's second branch: it did not, and nothing will be collected. */
    public static final String UNSUCCESSFUL = "project.unsuccessful";

    /** What this outcome is called in the vocabulary consumers switch on. */
    public static String eventTypeFor(CampaignOutcome outcome) {
        return switch (outcome) {
            case SUCCESSFUL -> SUCCEEDED;
            case UNSUCCESSFUL -> UNSUCCESSFUL;
        };
    }

    /**
     * The event for a campaign that has just been finalised.
     *
     * <p>Everything is read off the row after {@link Project#freezeOutcome} has written
     * it, including the instant, for the reason {@code finalisedAt} gives above.
     *
     * @throws IllegalStateException when the campaign has not been finalised, which would
     *     produce an event announcing a decision that was not made
     */
    public static CampaignFinalisedEvent of(Project project) {
        if (!project.isFinalised()) {
            throw new IllegalStateException(
                    "Campaign " + project.getId() + " is in " + project.getState() + " and has no frozen outcome");
        }
        return new CampaignFinalisedEvent(
                project.getId(),
                project.getCreatorId(),
                Money.of(project.getOutcomeGoalAmount(), project.getCurrency()),
                Money.of(project.getOutcomePledgedAmount(), project.getCurrency()),
                project.getOutcomeBackersCount(),
                project.getFinalizedAt());
    }
}
