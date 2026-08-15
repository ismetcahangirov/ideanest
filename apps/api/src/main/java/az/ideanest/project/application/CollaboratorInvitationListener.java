package az.ideanest.project.application;

import az.ideanest.project.application.ProjectEvents.CollaboratorInvited;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Sends the invitation the editor issued, after the transaction that issued it
 * has committed.
 *
 * <p>Sending inside the transaction would mean sending a link to a grant a later
 * rollback removed — and the recipient would then click a link that has never
 * been valid, which reads as the platform being broken rather than as a
 * transaction having failed.
 *
 * <p>The remaining window is a crash between the commit and the send: the grant
 * exists and no message arrives, and the creator has to revoke and invite again.
 * Closing that properly is the transactional outbox (#135).
 */
@Component
public class CollaboratorInvitationListener {

    private final CollaboratorInvitationNotifier notifier;

    public CollaboratorInvitationListener(CollaboratorInvitationNotifier notifier) {
        this.notifier = notifier;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCollaboratorInvited(CollaboratorInvited event) {
        notifier.sendCollaboratorInvitation(event.email(), event.token(), event.projectId(), event.invitedBy());
    }
}
