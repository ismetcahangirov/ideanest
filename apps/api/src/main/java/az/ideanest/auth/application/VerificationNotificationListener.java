package az.ideanest.auth.application;

import az.ideanest.auth.application.AuthEvents.EmailChangeNoticeToPreviousAddress;
import az.ideanest.auth.application.AuthEvents.EmailChangeRequested;
import az.ideanest.auth.application.AuthEvents.EmailVerificationRequested;
import az.ideanest.auth.application.AuthEvents.PasswordChanged;
import az.ideanest.auth.application.AuthEvents.PasswordResetRequested;
import az.ideanest.auth.application.AuthEvents.RegistrationAttemptedOnExistingAccount;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Sends what registration announced, after the transaction that announced it
 * has committed.
 *
 * <p>Sending inside the transaction would mean sending a verification link for
 * an account that a later rollback removed, and a message cannot be unsent.
 * After commit the account definitely exists.
 *
 * <p>The remaining window is a crash between the commit and the send: the
 * account is created and no email arrives, and the user has to ask for another
 * link. Closing that properly is the transactional outbox (#135), which writes
 * the intent to send in the same transaction as the account.
 */
@Component
public class VerificationNotificationListener {

    private final VerificationNotifier notifier;

    public VerificationNotificationListener(VerificationNotifier notifier) {
        this.notifier = notifier;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onVerificationRequested(EmailVerificationRequested event) {
        notifier.sendEmailVerification(event.email(), event.token(), event.locale());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRegistrationAttemptOnExistingAccount(RegistrationAttemptedOnExistingAccount event) {
        notifier.sendRegistrationAttemptOnExistingAccount(event.email(), event.locale());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPasswordResetRequested(PasswordResetRequested event) {
        notifier.sendPasswordReset(event.email(), event.token(), event.locale());
    }

    /**
     * The notice that a password changed.
     *
     * <p>After commit like everything else here, and the ordering matters more than
     * usual: a message saying "your password was changed" that arrives before the
     * change is committed — and is then rolled back — teaches people to ignore the
     * one message on this platform that is worth acting on immediately.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPasswordChanged(PasswordChanged event) {
        notifier.sendPasswordChanged(event.email(), event.locale());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onEmailChangeRequested(EmailChangeRequested event) {
        notifier.sendEmailChangeConfirmation(event.newEmail(), event.token(), event.locale());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onEmailChangeNotice(EmailChangeNoticeToPreviousAddress event) {
        notifier.sendEmailChangeNotice(event.previousEmail(), event.newEmail(), event.locale());
    }
}
