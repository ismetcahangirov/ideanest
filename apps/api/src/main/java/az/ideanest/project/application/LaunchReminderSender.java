package az.ideanest.project.application;

import az.ideanest.project.ProjectProperties;
import az.ideanest.project.domain.ProjectState;
import az.ideanest.project.infrastructure.ProjectRepository;
import az.ideanest.project.infrastructure.ReminderRepository;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Works through the people owed a launch notice, a batch at a time.
 *
 * <p><strong>Not transactional, on purpose.</strong> Each follower is its own
 * transaction inside {@link LaunchReminderDelivery}; a transaction around this
 * loop would be the batch-shaped failure that class exists to avoid. What lives
 * here is the ordering and the tolerance for failure: one bad row must not stop
 * the rest, which is the rule {@code AccountAnonymisationJob} states and for the
 * same reason — a backlog that stalls on a single row is a promise to everybody
 * behind it that we quietly stopped keeping.
 *
 * <p><strong>Both callers reach the same method.</strong>
 * {@link LaunchReminderListener} calls it the instant a launch commits, so a
 * creator does not watch their followers be told a minute later;
 * {@link LaunchReminderJob} calls it from §8.4's {@code reminder-sender} schedule
 * for every live campaign that still owes notices. Running both is safe because
 * the claim is a conditional update, and running both is <em>necessary</em>
 * because neither is sufficient: the event is lost to a crash between commit and
 * listener, and a launch performed by the future {@code campaign-launcher} job may
 * not publish one at all — while the sweep alone would make every launch feel a
 * minute slow. The sweep is the authority; the event is latency.
 */
@Component
public class LaunchReminderSender {

    private static final Logger log = LoggerFactory.getLogger(LaunchReminderSender.class);

    private final ProjectRepository projects;
    private final ReminderRepository reminders;
    private final LaunchReminderDelivery delivery;
    private final ProjectProperties properties;

    public LaunchReminderSender(
            ProjectRepository projects,
            ReminderRepository reminders,
            LaunchReminderDelivery delivery,
            ProjectProperties properties) {
        this.projects = projects;
        this.reminders = reminders;
        this.delivery = delivery;
        this.properties = properties;
    }

    /**
     * Tells everybody still waiting on this campaign that it has opened.
     *
     * <p>Refuses to do anything for a campaign that is not {@link ProjectState#LIVE}.
     * The state is re-read here rather than trusted from the caller because the
     * event may have been published by a transaction whose campaign has since moved
     * — and "the campaign you were waiting for is live" is a claim that has to be
     * true when it is sent, not when it was queued.
     *
     * @return how many messages this call produced
     */
    public int notifyFollowers(UUID projectId) {
        if (!isLive(projectId)) {
            return 0;
        }

        int batchSize = properties.reminders().sendBatchSize();
        int sent = 0;

        while (true) {
            List<UUID> pending = reminders.findPending(projectId, PageRequest.ofSize(batchSize));
            if (pending.isEmpty()) {
                break;
            }

            int failures = 0;
            for (UUID reminderId : pending) {
                try {
                    if (delivery.deliver(reminderId)) {
                        sent++;
                    }
                } catch (RuntimeException e) {
                    // The row stays unclaimed, so the next sweep tries again. One
                    // address that a future mail transport rejects must not cost
                    // everybody behind it their notification.
                    failures++;
                    log.error("Could not send launch reminder {} on project {}; it stays pending.", reminderId, projectId, e);
                }
            }

            // Nothing in this pass moved, so the next pass would read the same
            // rows and fail the same way. Stop; the schedule brings us back.
            if (failures == pending.size()) {
                break;
            }
            if (pending.size() < batchSize) {
                break;
            }
        }

        if (sent > 0) {
            log.info("Sent {} launch reminders for project {}.", sent, projectId);
        }
        return sent;
    }

    /**
     * Every live campaign that still owes somebody a notice, newest batch first.
     *
     * <p>Bounded by the same batch size, for the reason
     * {@code ideanest.user.anonymisation-batch-size} gives: a backlog built up
     * during an outage must not be attempted in one pass that overlaps its own next
     * tick.
     */
    @Transactional(readOnly = true)
    public List<UUID> projectsOwedNotices() {
        return reminders.findProjectsOwedNotices(
                ProjectState.LIVE, PageRequest.ofSize(properties.reminders().sendBatchSize()));
    }

    /**
     * Deliberately not annotated. It is one read, and a {@code @Transactional} here
     * would be a lie: this class is its own caller, and Spring's proxy does not see
     * a self-call — the annotation would read as a guarantee that was never
     * applied. The repository call opens its own transaction, which is all a single
     * read needs.
     */
    private boolean isLive(UUID projectId) {
        return projects.findById(projectId).map(project -> project.isLive()).orElse(false);
    }
}
