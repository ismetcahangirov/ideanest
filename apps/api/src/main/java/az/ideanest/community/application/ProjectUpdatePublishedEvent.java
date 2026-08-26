package az.ideanest.community.application;

import az.ideanest.community.domain.ProjectUpdate;
import java.time.Instant;
import java.util.UUID;

/**
 * {@code project.update_published}: a campaign's team has said something — §4.9's C-04.
 *
 * <p>Recorded through §8.3's outbox in the transaction that writes the update, so an update that
 * rolled back is one nothing was told about. The same arrangement {@link CommentPostedEvent}
 * uses, and the same aggregate: {@code project}, so a campaign's updates and its comments reach
 * a consumer in the order they were written rather than in no order at all.
 *
 * <h2>Why it exists, and what it deliberately is not</h2>
 *
 * <p>#127. The web client caches a campaign's public page and its update list, and a creator who
 * publishes an update sends people to a page that does not have it yet. Nothing in the service
 * announced the publication, so nothing could tell the cache — this is that announcement.
 *
 * <p><strong>It is not a notification.</strong> §8.2's table gives backers an update e-mail, and
 * that is #82's, with an audience to resolve and a preference to respect. This carries no
 * recipient and no body, because the one consumer today needs neither.
 *
 * <h2>Scheduled updates announce themselves late, and that is stated rather than hidden</h2>
 *
 * <p>{@code publishAt} may be in the future — {@code ProjectUpdateService} allows it and
 * {@code PublicProjectUpdates} hides an update until the instant arrives. This event is recorded
 * when the row is written, not when it becomes visible, so an update scheduled for tomorrow
 * invalidates a cache today and the page it refreshes still does not show it. That costs one
 * unnecessary render; the alternative is a sweep whose only job is to re-announce something, and
 * a page that is at most sixty seconds behind the moment an update appears is what the far
 * side's own window already guarantees.
 *
 * @param updateId which update
 * @param projectId the campaign, which is the aggregate and what a cache is keyed by
 * @param number the campaign's own numbering, so a consumer can tell a new update from a
 *     redelivered one without holding identifiers
 * @param publishedAt when it becomes visible, which is not necessarily when this was recorded
 */
public record ProjectUpdatePublishedEvent(UUID updateId, UUID projectId, int number, Instant publishedAt) {

    /** §7.2's aggregate name. {@code project}, for {@link CommentPostedEvent}'s reason. */
    public static final String AGGREGATE_TYPE = "project";

    public static final String EVENT_TYPE = "project.update_published";

    static ProjectUpdatePublishedEvent of(ProjectUpdate update) {
        return new ProjectUpdatePublishedEvent(
                update.getId(), update.getProjectId(), update.getNumber(), update.getPublishedAt());
    }
}
