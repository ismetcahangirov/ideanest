package az.ideanest.project.application;

import az.ideanest.project.ProjectProperties;
import az.ideanest.shared.jobs.ScheduledJob;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * §8.4's deadline reminders: the half of "launch and deadline reminders" that #39 left out.
 *
 * <p>#39 built the launch half and said plainly why the other one was not there — it needed a
 * notification preference model, and the "48 hours remaining" and "24 hours remaining" rows of
 * §4.10 had nowhere to go. #85 built the preference model, #245 built the port that computes an
 * audience, and #90 built the {@code saves} rows behind "saved project ending soon". This is
 * what that unblocked.
 *
 * <h2>A second job rather than a second responsibility on {@code reminder-sender}</h2>
 *
 * <p>§8.4's table gave both halves one row and one name, and this splits them. The reason is
 * the lease: {@code JobRunner} counts failures per job name and backs a failing job off, up to a
 * ten-minute cap and then {@code DEAD}. One job doing both would mean a database problem in the
 * deadline sweep backing off launch notices too — and {@link LaunchReminderJob} states that it
 * is the one sweep in the platform that is *not* indifferent to running late, because a launch
 * notice arriving an hour after the campaign opened is a materially worse message.
 *
 * <p>Two names, two lease rows, two failure budgets. §8.4 is updated to carry both.
 *
 * <h2>Every five minutes, not every minute</h2>
 *
 * <p>{@code reminder-sender} runs every minute because a launch notice is judged on promptness.
 * This one is not: the thresholds are 48 and 24 hours, and nobody can tell whether a
 * "48 hours remaining" message went out at 48:00 or 47:56. Five minutes is a twentieth of the
 * queries for a delivery nobody could distinguish, and the window has a lower bound so lateness
 * can never turn into a notice about a campaign that has closed.
 *
 * <h2>Both thresholds in one pass</h2>
 *
 * <p>Rather than a job each. They ask the same question of the same table with a different
 * constant, and a campaign that launched with less than 24 hours to run crosses both at once —
 * which is correct and which two independently scheduled jobs would make arrive minutes apart
 * for no reason. Largest first, so that a campaign crossing both is announced in the order a
 * reader would expect to receive them.
 */
@Component
public class DeadlineReminderJob implements ScheduledJob {

    private static final Logger log = LoggerFactory.getLogger(DeadlineReminderJob.class);

    private final DeadlineReminderSender sender;
    private final ProjectProperties properties;

    public DeadlineReminderJob(DeadlineReminderSender sender, ProjectProperties properties) {
        this.sender = sender;
        this.properties = properties;
    }

    @Override
    public String name() {
        return "deadline-reminder";
    }

    /**
     * A property so that the test profile can set it to {@code -} and drive
     * {@link #announceDueDeadlines()} directly — a timer firing in the background of a test
     * suite acts on the very rows a test is about to assert on.
     */
    @Override
    public String schedule() {
        return properties.reminders().deadlineSchedule();
    }

    @Override
    public void run() {
        announceDueDeadlines();
    }

    /**
     * One pass over both thresholds.
     *
     * <p>Bounded per threshold rather than exhaustive, as every job here is: a backlog must not
     * become one pass that overlaps its own next tick. What is left is five minutes away, and
     * the ordering by deadline means the remainder is always the least urgent part of it.
     *
     * @return how many campaigns this pass announced
     */
    public int announceDueDeadlines() {
        ProjectProperties.Reminders limits = properties.reminders();

        int announced = 0;
        for (int thresholdHours : limits.deadlineThresholdHours()) {
            List<UUID> nearing = sender.nearing(thresholdHours, limits.deadlineBatchSize());

            for (UUID projectId : nearing) {
                try {
                    if (sender.announce(projectId, thresholdHours)) {
                        announced++;
                    }
                } catch (RuntimeException e) {
                    // One campaign per iteration, and one failure must not stop the rest: every
                    // campaign behind this one in the list is also closing. The threshold stays
                    // unclaimed, so the next pass tries again.
                    log.error(
                            "Could not announce the {}h deadline for project {}; it stays unclaimed.",
                            thresholdHours,
                            projectId,
                            e);
                }
            }
        }

        if (announced > 0) {
            log.info("Announced {} campaigns crossing a deadline threshold.", announced);
        }
        return announced;
    }
}
