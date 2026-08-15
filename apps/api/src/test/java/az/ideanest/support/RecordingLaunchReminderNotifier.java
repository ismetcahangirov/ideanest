package az.ideanest.support;

import az.ideanest.project.application.LaunchReminderNotifier;
import az.ideanest.shared.EmailAddress;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A notifier that remembers instead of sending, and can be told to fail.
 *
 * <p>The same reasoning as {@link RecordingCollaboratorInvitationNotifier}: there
 * is no mail transport, and the questions worth asking of launch reminders are
 * about <em>who</em> was told and <em>how many times</em>, which the log adapter
 * cannot be asked.
 *
 * <p>{@link #failNext(boolean)} exists because the resumability of the sweep is
 * not observable otherwise. A send that throws must roll the claim back and leave
 * the row for the next pass, and the only way to prove that is to make one throw.
 */
public class RecordingLaunchReminderNotifier implements LaunchReminderNotifier {

    /** One launch notice that would have been sent. */
    public record SentReminder(EmailAddress email, UUID accountId, UUID projectId, String unsubscribeToken) {
    }

    private final List<SentReminder> sent = new CopyOnWriteArrayList<>();
    private final AtomicBoolean failing = new AtomicBoolean(false);

    @Override
    public void sendLaunchReminder(
            EmailAddress email, UUID accountId, UUID projectId, String unsubscribeToken) {

        if (failing.get()) {
            throw new IllegalStateException("The test asked this send to fail");
        }
        sent.add(new SentReminder(email, accountId, projectId, unsubscribeToken));
    }

    /** Everything sent for one campaign, in the order it was sent. */
    public List<SentReminder> sentFor(UUID projectId) {
        return sent.stream().filter(reminder -> reminder.projectId().equals(projectId)).toList();
    }

    public long timesSentTo(EmailAddress email) {
        return sent.stream().filter(reminder -> reminder.email().equals(email)).count();
    }

    /** Makes every subsequent send throw, until it is turned off again. */
    public void failNext(boolean fail) {
        failing.set(fail);
    }

    public void clear() {
        sent.clear();
        failing.set(false);
    }
}
