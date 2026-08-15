package az.ideanest.project.application;

import az.ideanest.shared.EmailAddress;
import java.util.UUID;

/**
 * What the project module announces once its transaction has committed.
 *
 * <p>The same reasoning as {@code AuthEvents}: a message sent inside a
 * transaction that then rolls back cannot be unsent, and the recipient would
 * hold an invitation to a campaign nobody was invited to.
 *
 * <p>This is not yet the transactional outbox (#135). A crash between the commit
 * and the send loses the message, and the creator has to invite the person
 * again.
 */
public final class ProjectEvents {

    private ProjectEvents() {
    }

    /**
     * Somebody was invited to work on a campaign and needs the link.
     *
     * @param token the raw invitation token. In this record and in the message it
     *     produces, and nowhere else — the row holds only its hash
     */
    public record CollaboratorInvited(EmailAddress email, String token, UUID projectId, UUID invitedBy) {
    }

    /**
     * A campaign went live, and the people holding a reminder for it are owed the
     * message they asked for (§4.10, "Reminder: project launched").
     *
     * <p><strong>This event is a latency optimisation, not the mechanism.</strong>
     * What actually guarantees delivery is the scheduled sweep of §8.4's
     * {@code reminder-sender}, which asks the database which live campaigns still
     * owe notices — so a crash between the commit and this listener, or a launch
     * performed by the future {@code campaign-launcher} job, costs at most a
     * minute rather than the whole notification. The event exists so that a
     * creator who presses "launch" does not watch their followers be told a minute
     * later, and dropping it entirely would change nothing except that.
     *
     * @param projectId which campaign. Deliberately nothing else: everything the
     *     sender needs is read inside its own transaction, because a payload
     *     carrying addresses would be a copy of personal data travelling through
     *     an in-process event bus for no reason
     */
    public record ProjectLaunched(UUID projectId) {
    }
}
