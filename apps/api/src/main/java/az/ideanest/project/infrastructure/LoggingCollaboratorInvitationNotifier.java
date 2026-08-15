package az.ideanest.project.infrastructure;

import az.ideanest.project.ProjectProperties;
import az.ideanest.project.application.CollaboratorInvitationNotifier;
import az.ideanest.shared.EmailAddress;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * A stand-in until transactional email exists (#86).
 *
 * <p>It writes a line saying a message would have been sent. It writes the token
 * itself <strong>only</strong> when
 * {@code ideanest.project.collaborators.log-invitation-links} is on, which it is in
 * {@code local} and nowhere else — the same rule
 * {@code LoggingVerificationNotifier} follows, and for a stronger reason here than
 * there: an invitation link in a production log is edit access to an unlaunched
 * campaign for anybody who can read logs, and that set is always larger than it
 * looks.
 *
 * <p>Without a real sender, a deployed environment cannot complete an invitation.
 * That is the correct state for it to be in: it fails visibly, rather than
 * appearing to work while nothing arrives.
 */
@Component
public class LoggingCollaboratorInvitationNotifier implements CollaboratorInvitationNotifier {

    private static final Logger log = LoggerFactory.getLogger(LoggingCollaboratorInvitationNotifier.class);

    private final boolean logLinks;

    public LoggingCollaboratorInvitationNotifier(ProjectProperties properties) {
        this.logLinks = properties.collaborators().logInvitationLinks();
    }

    @Override
    public void sendCollaboratorInvitation(EmailAddress email, String token, UUID projectId, UUID invitedBy) {
        if (logLinks) {
            log.info("Collaborator invitation token for {} on project {}: {}", email, projectId, token);
        } else {
            // EmailAddress masks itself, so this line records that an invitation
            // was issued without publishing the address it went to.
            log.info(
                    "Collaborator invitation queued for {} on project {} by {}. No mail transport is configured (#86).",
                    email,
                    projectId,
                    invitedBy);
        }
    }
}
