package az.ideanest.project.infrastructure;

import az.ideanest.project.ProjectProperties;
import az.ideanest.project.application.LaunchReminderNotifier;
import az.ideanest.shared.EmailAddress;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * A stand-in until transactional email (#86) and the notification service (#85)
 * exist.
 *
 * <p>It writes a line saying a message would have been sent. It writes the
 * unsubscribe token itself <strong>only</strong> when
 * {@code ideanest.project.reminders.log-unsubscribe-links} is on, which it is in
 * {@code local} and nowhere else — the same rule
 * {@code LoggingCollaboratorInvitationNotifier} follows, for a weaker reason than
 * there: a leaked unsubscribe link removes one person from one campaign's list,
 * which is a nuisance rather than a takeover. It is still off by default, because
 * "somebody who can read the logs can unsubscribe our followers" is not a
 * property worth having either.
 *
 * <p>Without a real sender, a deployed environment cannot actually tell anybody a
 * campaign launched — but it <em>will</em> stamp the row as notified, because the
 * stamp is what stops the sweep from looping. That is the correct behaviour for a
 * port with no implementation behind it: it fails visibly in the log, once per
 * follower, rather than quietly re-reading the same rows every minute forever.
 */
@Component
public class LoggingLaunchReminderNotifier implements LaunchReminderNotifier {

    private static final Logger log = LoggerFactory.getLogger(LoggingLaunchReminderNotifier.class);

    private final boolean logLinks;

    public LoggingLaunchReminderNotifier(ProjectProperties properties) {
        this.logLinks = properties.reminders().logUnsubscribeLinks();
    }

    @Override
    public void sendLaunchReminder(
            EmailAddress email, UUID accountId, UUID projectId, String unsubscribeToken) {

        if (logLinks) {
            log.info(
                    "Launch reminder for {} on project {}; unsubscribe token: {}",
                    email,
                    projectId,
                    unsubscribeToken);
            return;
        }

        // EmailAddress masks itself, so this line records that a launch notice was
        // due without publishing the address it was due to (§17.4).
        log.info(
                "Launch reminder for {} on project {} (account {}). No mail transport is configured (#86).",
                email,
                projectId,
                accountId);
    }
}
