package az.ideanest.project.application;

import az.ideanest.project.domain.Reminder;
import az.ideanest.project.infrastructure.ReminderRepository;
import az.ideanest.shared.EmailAddress;
import az.ideanest.shared.SecureTokens;
import az.ideanest.user.application.UserAccounts;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * One follower, one transaction, one message.
 *
 * <p><strong>This class exists because the transaction boundary has to be around
 * one row.</strong> A batch in one transaction has the wrong failure mode in both
 * directions: a rollback after twenty successful sends leaves twenty people
 * notified and unstamped, and the next sweep tells them again; and a commit that
 * covers rows nobody managed to send stamps people who heard nothing. Around a
 * single row, both disappear — the stamp and the message are the same unit of
 * work. It is a separate bean rather than a method on {@link LaunchReminderSender}
 * because Spring's {@code @Transactional} is a proxy, and a self-call would be no
 * transaction at all, which is precisely the bug this arrangement exists to
 * prevent.
 *
 * <p><strong>Claim, then send, inside the transaction.</strong> The order is
 * deliberate and it is the opposite of {@link CollaboratorInvitationListener}. An
 * invitation must not be sent for a grant a rollback removed. Here the campaign is
 * already live and committed, and the only thing in this transaction is the stamp
 * that says this person was told — so sending after the commit would allow a stamp
 * that is true while no message was ever produced, which is the failure that
 * silently drops somebody from a launch announcement. With the send inside, a
 * notifier that throws rolls the stamp back and the next sweep tries again.
 *
 * <p><strong>What is still open.</strong> A crash between the notifier returning
 * and the commit sends one person a second copy on the next sweep. That is the
 * ordinary at-least-once window and it cannot be closed without a transactional
 * outbox, which is #135 — the same limitation
 * {@code CollaboratorInvitationListener} records for its own send, at the other
 * end of the trade. One duplicate launch notice is the right side of it; a silent
 * omission is not.
 */
@Component
public class LaunchReminderDelivery {

    private static final Logger log = LoggerFactory.getLogger(LaunchReminderDelivery.class);

    private final ReminderRepository reminders;
    private final UserAccounts users;
    private final LaunchReminderNotifier notifier;
    private final Clock clock;

    public LaunchReminderDelivery(
            ReminderRepository reminders,
            UserAccounts users,
            LaunchReminderNotifier notifier,
            Clock clock) {
        this.reminders = reminders;
        this.users = users;
        this.notifier = notifier;
        this.clock = clock;
    }

    /**
     * Notifies one follower, if nobody has already.
     *
     * <p><strong>{@code REQUIRES_NEW}, and it is load-bearing.</strong> One caller
     * is {@link LaunchReminderListener}, which runs in the after-commit phase of the
     * transaction that launched the campaign. Spring still reports that transaction
     * as active there — it has committed but not completed — so the default
     * {@code REQUIRED} joins it and every write fails with "no transaction is in
     * progress". The other caller, the scheduled sweep, has no transaction at all
     * and would be equally happy with the default; asking for a new one covers both
     * and is the only propagation under which the claim is actually committed.
     *
     * @return true when a message was handed to the notifier. False means the row
     *     had gone, somebody else claimed it, or there was nowhere to write —
     *     none of which is an error, and all of which leave the row settled so the
     *     sweep does not come back to it
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean deliver(UUID reminderId) {
        Reminder reminder = reminders.findById(reminderId).orElse(null);
        if (reminder == null) {
            // Withdrawn between the sweep's read and now. Somebody asking not to
            // be emailed a moment before we email them is the case this ordering
            // gets right.
            return false;
        }
        if (reminder.isNotified()) {
            return false;
        }

        // Read before the claim: it is a bulk update that clears the persistence
        // context, so this instance is detached the moment it runs.
        UUID projectId = reminder.getProjectId();
        UUID accountId = reminder.getUserId();
        EmailAddress destination = destinationOf(reminder);

        // Truncated to what the column can hold, for the reason
        // ProjectTransitionService gives: PostgreSQL stores microseconds, and a
        // stamp written at nanosecond precision comes back as a different instant.
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);

        // The new link goes into the message this call is about to produce, and
        // replaces the one handed back at registration. See ReminderRepository#claim.
        String unsubscribeToken = SecureTokens.generate();

        if (reminders.claim(reminderId, now, SecureTokens.hash(unsubscribeToken)) == 0) {
            // Another replica's sweep got there first. Two instances running the
            // same timer is the normal state of affairs, and this is where that is
            // made safe rather than merely tolerated.
            return false;
        }

        if (destination == null) {
            // An account that has been anonymised since it asked (§17.4): the
            // reference survives and the address behind it does not. The row is
            // claimed anyway, deliberately — leaving it unstamped would make the
            // sweep pick it up again every minute for ever.
            log.info("Launch reminder {} on project {} has no usable address; nothing was sent.", reminderId, projectId);
            return false;
        }

        notifier.sendLaunchReminder(destination, accountId, projectId, unsubscribeToken);
        return true;
    }

    /**
     * Where to write, resolved now rather than when the reminder was registered.
     *
     * <p>An account's address is read from {@code users} at this moment, which is
     * the reason {@code reminders} does not copy it: somebody who changed their
     * address, or closed their account, between asking and the launch gets the
     * right answer without this table having to be swept. An address with no
     * account behind it is on the row, because there is nowhere else it could be.
     *
     * @return null when there is nothing to write to, which today means an
     *     anonymised account
     */
    private EmailAddress destinationOf(Reminder reminder) {
        if (!reminder.isForAccount()) {
            return reminder.getEmail();
        }
        return users.findById(reminder.getUserId()).map(account -> account.email()).orElse(null);
    }
}
