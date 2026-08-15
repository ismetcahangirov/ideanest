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
}
