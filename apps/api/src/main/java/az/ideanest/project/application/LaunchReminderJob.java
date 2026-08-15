package az.ideanest.project.application;

import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * §8.4's {@code reminder-sender}, in the half of it this issue owns.
 *
 * <p>The table gives the job two responsibilities, "launch and deadline
 * reminders". Only the first is here: a deadline reminder needs a campaign with a
 * deadline, a notification preference model, and the "48 hours remaining" and "24
 * hours remaining" rows of §4.10, none of which exist. Writing half of it now
 * would be a job that looks finished.
 *
 * <p><strong>This belongs on the durable scheduler, not here.</strong> There is no
 * job queue yet (#134), so this is Spring's {@code @Scheduled}: an in-process
 * timer with no record of what it did, no retry, and no visibility. It is adequate
 * for the same reasons {@code AccountAnonymisationJob} is — the work is idempotent
 * and bounded — with one difference worth stating: this one is <em>not</em>
 * indifferent to running late. A launch notice that arrives an hour after the
 * campaign opened is a worse message than one that arrives in a minute, which is
 * why {@link LaunchReminderListener} exists beside it rather than instead of it.
 *
 * <p><strong>On more than one instance</strong> every replica runs this on its own
 * timer, so several will look for owed notices at once. That is safe rather than
 * merely tolerable: {@link LaunchReminderDelivery} claims each row with a
 * conditional update, so exactly one caller sends and the others find it already
 * done. The cost of not having a leader election is some duplicated reads, which
 * is the right trade against a lock nobody maintains.
 */
@Component
public class LaunchReminderJob {

    private static final Logger log = LoggerFactory.getLogger(LaunchReminderJob.class);

    private final LaunchReminderSender sender;

    public LaunchReminderJob(LaunchReminderSender sender) {
        this.sender = sender;
    }

    /**
     * The schedule is a property so that the test profile can set it to {@code -}
     * and drive {@link #sendDueReminders()} directly. A timer firing in the
     * background of a test suite is a source of failures that reproduce once a
     * fortnight — and this one would fire every minute.
     */
    @Scheduled(cron = "${ideanest.project.reminders.send-schedule}", zone = "UTC")
    public void run() {
        sendDueReminders();
    }

    /**
     * One pass: every live campaign that still owes somebody a launch notice.
     *
     * @return how many messages this pass produced
     */
    public int sendDueReminders() {
        List<UUID> owed = sender.projectsOwedNotices();

        int sent = 0;
        for (UUID projectId : owed) {
            try {
                sent += sender.notifyFollowers(projectId);
            } catch (RuntimeException e) {
                // One campaign per iteration, and one failure must not stop the
                // rest: the campaigns behind this one in the list launched too.
                log.error("Could not send launch reminders for project {}; they stay pending.", projectId, e);
            }
        }

        if (sent > 0) {
            log.info("Sent {} launch reminders across {} campaigns.", sent, owed.size());
        }
        return sent;
    }
}
