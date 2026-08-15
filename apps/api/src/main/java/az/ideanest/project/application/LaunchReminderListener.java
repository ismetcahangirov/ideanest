package az.ideanest.project.application;

import az.ideanest.project.application.ProjectEvents.ProjectLaunched;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Starts the launch sweep the moment a launch has committed.
 *
 * <p>After the commit, for the reason {@link CollaboratorInvitationListener}
 * gives: telling somebody a campaign is live inside the transaction that made it
 * live means telling them so even when that transaction rolls back, and a
 * follower cannot be un-told.
 *
 * <p><strong>This listener is not the delivery guarantee.</strong> A crash between
 * the commit and this method loses nothing but a minute — the scheduled sweep in
 * {@link LaunchReminderJob} asks the database which live campaigns still owe
 * notices and finds the same rows. That is why the failure below is swallowed with
 * a log rather than rethrown: an exception here would travel back into the
 * after-commit callback of a transaction that has already committed, where it can
 * achieve nothing except turning a successful launch into a 500 for the creator.
 */
@Component
public class LaunchReminderListener {

    private static final Logger log = LoggerFactory.getLogger(LaunchReminderListener.class);

    private final LaunchReminderSender sender;

    public LaunchReminderListener(LaunchReminderSender sender) {
        this.sender = sender;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onProjectLaunched(ProjectLaunched event) {
        try {
            sender.notifyFollowers(event.projectId());
        } catch (RuntimeException e) {
            log.error(
                    "Launch reminders for project {} could not be started; the scheduled sweep will retry.",
                    event.projectId(),
                    e);
        }
    }
}
