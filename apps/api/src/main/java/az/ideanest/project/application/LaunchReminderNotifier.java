package az.ideanest.project.application;

import az.ideanest.shared.EmailAddress;
import java.util.UUID;

/**
 * Telling one person that the campaign they asked about has opened.
 *
 * <p>A port, for the reason {@link CollaboratorInvitationNotifier} is one:
 * transactional email — templates, retries, bounces, suppression lists — is #86,
 * the notification service that fans one event out to email, push, and the in-app
 * inbox is #85, and neither belongs inside the rule about who gets told. What
 * belongs here is the decision about <em>what</em> is sent, which is what this
 * interface names.
 *
 * <p><strong>§4.10 makes this three channels and this port is one.</strong>
 * "Reminder: project launched" is email, push, and in-app. Only the first is
 * expressible today: there is no device registry, no {@code notifications} table,
 * and no preference model, so a port with three methods would be two methods that
 * nobody could implement. #85 replaces this interface with a call into the
 * notification service, which is why the caller depends on the abstraction rather
 * than on a mail sender.
 *
 * <p><strong>Called inside the claiming transaction, deliberately.</strong> That
 * is the opposite of {@link CollaboratorInvitationListener}, and the difference is
 * real: an invitation must not be sent for a grant a rollback removed, whereas
 * here the campaign is already live and committed, and the only thing in the
 * transaction is the stamp that says this person was told. Sending after that
 * commit would mean a stamp that can be true while no message was ever produced.
 * See {@code LaunchReminderDelivery} for the failure the arrangement leaves open
 * and which issue closes it.
 */
public interface LaunchReminderNotifier {

    /**
     * @param email where to write. Resolved at send time — from {@code users} for
     *     an account's reminder, from the row for somebody with no account — so
     *     that an address changed or anonymised since the reminder was registered
     *     is never the address used
     * @param accountId the account this reminder belongs to, or null when nobody
     *     behind it has registered. Present so that #85 can also put the notice in
     *     that account's in-app inbox rather than only in its mailbox
     * @param projectId which campaign. Not its title: the sender is the component
     *     that decides how much of a campaign to put in a message, and this one is
     *     public by the time it is sent, unlike an invitation's
     * @param unsubscribeToken the raw token for the "stop reminding me" link.
     *     Never stored — the row holds only its SHA-256 — and out of scope the
     *     moment this method returns. It is in the message because a launch notice
     *     without a way out is the thing that turns a pre-launch page into spam
     */
    void sendLaunchReminder(EmailAddress email, UUID accountId, UUID projectId, String unsubscribeToken);
}
