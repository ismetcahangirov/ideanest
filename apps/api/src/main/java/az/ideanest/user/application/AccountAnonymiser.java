package az.ideanest.user.application;

import az.ideanest.user.infrastructure.UserRepository;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Anonymising one account, in one transaction.
 *
 * <p><strong>Anonymisation, not deletion.</strong> §17.4 says the grace period
 * ends in anonymisation and that financial records are kept for the statutory
 * period, and those two sentences are the same sentence. A pledge is a
 * financial record; "pledge #123 was made by user X" has to remain true after X
 * leaves, or the ledger no longer reconciles against the money that actually
 * moved. Every one of those rows is a foreign key to {@code users.id}, so the
 * row survives and its contents do not.
 *
 * <p><strong>What is overwritten:</strong> the email address, the display name,
 * the public slug, the avatar, the biography, the proof that the address was
 * verified, the password credential, every unspent verification link, and the
 * device label, user agent, and IP address of every session.
 *
 * <p><strong>What survives:</strong> the row's identifier and timestamps, the
 * locale and currency (neither identifies anybody, and the currency is the unit
 * the retained financial rows are denominated in), the sessions themselves —
 * when they started and why they ended is a security record with no person left
 * in it — the hashes of refresh tokens, and every financial row that refers to
 * this identifier.
 *
 * <p>Separate from {@link AccountAnonymisationJob} for the same reason
 * {@code SessionRevoker} is separate: the transaction is per account, and a
 * self-invocation from the loop would not pass through the proxy, so the
 * annotation would be silently ignored and the whole batch would run in one
 * transaction — or in none.
 */
@Service
public class AccountAnonymiser {

    private static final Logger log = LoggerFactory.getLogger(AccountAnonymiser.class);

    private final UserRepository users;
    private final AccountSecurity security;

    public AccountAnonymiser(UserRepository users, AccountSecurity security) {
        this.users = users;
        this.security = security;
    }

    /**
     * Anonymises the account if its grace period has elapsed.
     *
     * <p>Idempotent, and safe when two instances call it at once: the row is
     * locked first, so the second caller waits, re-reads, finds
     * {@code anonymised_at} already set, and returns false. Everything inside
     * the transaction is also individually repeatable, so a crash halfway
     * leaves the account still due and the next run finishes the job.
     *
     * @return whether this call was the one that did the work
     */
    @Transactional
    public boolean anonymise(UUID userId, Instant now) {
        return users.findByIdForUpdate(userId)
                .filter(user -> user.isAnonymisationDue(now))
                .map(user -> {
                    user.anonymise(now);
                    // Written before the credentials are destroyed, and the
                    // order is not cosmetic: the statements behind forget() are
                    // bulk updates that clear the persistence context, which
                    // detaches this entity. Mutating it afterwards would leave
                    // the profile untouched, the job would report success, and
                    // the account would stay readable under its real name.
                    users.saveAndFlush(user);
                    security.forget(userId);
                    log.info("Account {} anonymised.", userId);
                    return true;
                })
                .orElse(false);
    }
}
