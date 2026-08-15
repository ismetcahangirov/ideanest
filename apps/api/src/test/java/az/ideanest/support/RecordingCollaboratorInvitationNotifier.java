package az.ideanest.support;

import az.ideanest.project.application.CollaboratorInvitationNotifier;
import az.ideanest.shared.EmailAddress;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A notifier that remembers instead of sending.
 *
 * <p>The same reasoning as {@link RecordingVerificationNotifier}: the invitation
 * token exists in the message and as a hash in the database, and a test that read
 * the row would prove only that <em>a</em> token exists — not that the right one
 * reached the right address. So the test accepts the invitation with what was sent,
 * which is the only way to prove the two match.
 */
public class RecordingCollaboratorInvitationNotifier implements CollaboratorInvitationNotifier {

    /** One issued invitation. */
    public record SentInvitation(EmailAddress email, String token, UUID projectId, UUID invitedBy) {
    }

    private final List<SentInvitation> invitations = new CopyOnWriteArrayList<>();

    @Override
    public void sendCollaboratorInvitation(EmailAddress email, String token, UUID projectId, UUID invitedBy) {
        invitations.add(new SentInvitation(email, token, projectId, invitedBy));
    }

    /** The most recent token sent to an address, if any. */
    public Optional<String> tokenSentTo(EmailAddress email) {
        return invitations.stream()
                .filter(sent -> sent.email().equals(email))
                .map(SentInvitation::token)
                .reduce((first, second) -> second);
    }

    public long invitationsSentTo(EmailAddress email) {
        return invitations.stream().filter(sent -> sent.email().equals(email)).count();
    }

    public void clear() {
        invitations.clear();
    }
}
