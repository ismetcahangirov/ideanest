package az.ideanest.project.application;

import az.ideanest.project.domain.Project;
import java.time.Instant;
import java.util.UUID;

/**
 * {@code project.launched}: a campaign opened, announced through §8.3's outbox.
 *
 * <p><strong>Not the same thing as {@link ProjectEvents.ProjectLaunched}, and both stay.</strong>
 * That one is an in-process Spring event published after the commit, and its own comment says
 * what it is for: it wakes the launch-reminder sweep immediately so that a creator does not
 * watch their pre-launch list be told a minute later. It is latency, explicitly not a
 * guarantee, and it is safe to lose because the sweep asks the database the same question a
 * minute later.
 *
 * <p>This one is a durable event, and #245 is why it had to exist. §4.10's "followed creator
 * launched" goes to everybody following the creator, and that audience has no sweep behind it
 * — there is no {@code notified_at} column on a follow and there should not be one, because a
 * follow is a standing relationship and not an outstanding obligation. So the announcement has
 * to be the thing that cannot be lost, which is an outbox row written by the launch's own
 * transaction.
 *
 * <p>The two are not redundant, then, and the distinction is worth keeping in mind when reading
 * {@code ProjectTransitionService#launch}: it publishes both, one for promptness and one for
 * certainty.
 *
 * <h2>Why the creator travels</h2>
 *
 * <p>{@code Outbox} asks for "enough to route on, and no more", assuming a consumer that can
 * read the rest inside its own transaction. The notification module cannot — {@code projects}
 * is this module's table — and the audience for this event is defined by the creator rather
 * than by the campaign. {@code CampaignFinalisedEvent} makes the same argument for the same
 * field.
 *
 * <p>Nothing else travels. There is no title here, deliberately: the notification module reads
 * one through {@code shared.project.ProjectSummaries} at translation time, and a copy in the
 * payload would be a second source for the same fact that could disagree with the first.
 *
 * <p>This is a copy of the contract in the sense {@code CampaignFinalisedEvent} is: the
 * notification module declares its own reading of the same JSON, neither imports the other, and
 * the field names below are therefore the contract.
 *
 * @param projectId which campaign. Also the aggregate identifier, so §8.3 orders this before
 *     any later event about the same campaign
 * @param creatorId whose campaign it is, and the account whose followers are the audience
 * @param launchedAt when it opened, read back from the row rather than taken from the clock a
 *     second time, so the event and {@code projects.launched_at} cannot disagree
 * @param deadline when it closes. Carried because a launch notice that does not say how long
 *     somebody has is a notice that invites them to come back later
 */
public record ProjectLaunchedEvent(UUID projectId, UUID creatorId, Instant launchedAt, Instant deadline) {

    /** §7.2's aggregate name, shared with every other event about a campaign. */
    public static final String AGGREGATE_TYPE = "project";

    public static final String EVENT_TYPE = "project.launched";

    static ProjectLaunchedEvent of(Project project) {
        return new ProjectLaunchedEvent(
                project.getId(), project.getCreatorId(), project.getLaunchedAt(), project.getDeadline());
    }
}
