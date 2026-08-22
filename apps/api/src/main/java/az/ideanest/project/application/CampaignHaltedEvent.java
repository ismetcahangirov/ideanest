package az.ideanest.project.application;

import az.ideanest.project.domain.Project;
import az.ideanest.project.domain.ProjectState;
import java.time.Instant;
import java.util.UUID;

/**
 * {@code project.canceled} and {@code project.suspended}: a campaign stopped before its
 * deadline, announced — #103.
 *
 * <p>Recorded through §8.3's outbox inside the transaction that performs the transition,
 * exactly as {@link CampaignFinalisedEvent} is, and for the same reason: "we told
 * everybody the campaign is over" must not be true of a campaign that is still running,
 * and a campaign that stopped must not be one nobody was told about.
 *
 * <h2>Why this event exists at all</h2>
 *
 * <p>Because stopping a campaign is not only a state change: every pledge on it has to
 * stop being a pledge, and the places those pledges hold have to go back on sale. Both
 * are the pledge module's, {@code pledges} is its table, and this module may not read it
 * — {@code ModuleBoundaryTests} fails the build over that, and the pledge module already
 * depends on this one through {@code PledgeAcceptance}, so a call in the other direction
 * would be a cycle rather than a coupling. The event is what makes the two halves one
 * commit and still leaves them in two modules.
 *
 * <h2>The reason is the event type, not a field</h2>
 *
 * <p>One record and two names, following {@link CampaignFinalisedEvent}. A creator
 * stopping their own campaign and trust and safety stopping it for them are different
 * facts to everybody downstream — different messages, different support conversations,
 * different questions from a regulator — and a consumer that had to switch on a string
 * to find this module's event and then on a field to find out what it said would be a
 * consumer with two things that can disagree.
 *
 * @param projectId which campaign. Also the aggregate identifier, so §8.3's ordering is
 *     per campaign
 * @param creatorId whose campaign it is. The recipient every consumer wants and the one
 *     nobody else can look up without reading {@code projects}
 * @param reason what the creator or the moderator wrote. Travels because §5.5 requires
 *     backers to be told why, and the consumer that will write to them cannot read
 *     {@code project_state_transitions} to find out
 * @param haltedAt when it stopped, read off the row rather than taken from the clock
 *     again so the event and the transition cannot disagree
 */
public record CampaignHaltedEvent(UUID projectId, UUID creatorId, String reason, Instant haltedAt) {

    /** §8.3's aggregate type, shared with every other event about a campaign. */
    public static final String AGGREGATE_TYPE = "project";

    /** The creator stopped it. §6.1's {@code LIVE → CANCELED}. */
    public static final String CANCELED = "project.canceled";

    /** Trust and safety stopped it. §6.1's {@code LIVE → SUSPENDED}. */
    public static final String SUSPENDED = "project.suspended";

    public static CampaignHaltedEvent of(Project project, String reason) {
        return new CampaignHaltedEvent(
                project.getId(), project.getCreatorId(), reason, project.getUpdatedAt());
    }

    /**
     * Which of the two names this state is announced under.
     *
     * @throws IllegalArgumentException for any other state. A campaign that reached
     *     {@code UNSUCCESSFUL} is {@link CampaignFinalisedEvent}'s, and announcing it
     *     here would tell every consumer a deadline was a suspension
     */
    public static String eventTypeFor(ProjectState state) {
        return switch (state) {
            case CANCELED -> CANCELED;
            case SUSPENDED -> SUSPENDED;
            default -> throw new IllegalArgumentException("A campaign in " + state + " has not been halted");
        };
    }
}
