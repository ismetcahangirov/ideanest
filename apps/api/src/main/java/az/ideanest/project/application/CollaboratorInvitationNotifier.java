package az.ideanest.project.application;

import az.ideanest.shared.EmailAddress;
import java.util.UUID;

/**
 * Delivering an invitation to the address it was issued to.
 *
 * <p>A port, for the reason {@code VerificationNotifier} is one: transactional
 * email — templates, retries, bounces, suppression lists — is #86 and does not
 * belong inside the rule about who may be invited. What belongs here is the
 * decision about <em>what</em> is sent, which is what this interface names.
 *
 * <p><strong>The token crosses this interface and nothing else.</strong> It exists
 * in exactly two places: the message this sends, and its SHA-256 in the
 * {@code collaborators} row. It is deliberately absent from the API response to
 * the inviter — a creator who could read the token out of their own response could
 * accept the invitation themselves, and the whole point of sending it to the
 * address is that only the person holding that mailbox can.
 */
public interface CollaboratorInvitationNotifier {

    /**
     * @param email the person invited. May well have no account here yet, which is
     *     the case this whole flow exists to support
     * @param token the raw invitation token. Never stored, never logged outside
     *     local development, out of scope the moment this method returns
     * @param projectId which campaign, so a real implementation can name it in the
     *     message. Not the campaign's title: an unlaunched title is confidential,
     *     and the sender is the component that decides how much of it to reveal
     * @param invitedBy who issued the invitation. An invitation from a stranger is
     *     a phishing email as far as the recipient can tell
     */
    void sendCollaboratorInvitation(EmailAddress email, String token, UUID projectId, UUID invitedBy);
}
