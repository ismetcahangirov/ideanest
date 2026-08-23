package az.ideanest.user.application;

import az.ideanest.shared.EmailAddress;
import az.ideanest.shared.Slugs;
import az.ideanest.user.domain.User;
import az.ideanest.user.infrastructure.UserRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The user module's front door.
 *
 * <p>Every other module goes through here, and gets {@link UserAccount} back
 * rather than the entity. That is the module boundary in practice, and
 * {@code ModuleBoundaryTests} enforces it.
 */
@Service
public class UserAccounts {

    /**
     * How many numbered slugs to try before giving up on a readable one. Ten
     * people can be called Ismet; the eleventh gets a random suffix rather than
     * a query loop that grows with the popularity of a name.
     */
    private static final int SLUG_ATTEMPTS = 10;

    /** Leaves room for a suffix inside the sixty-character column limit. */
    private static final int SLUG_BASE_LIMIT = 50;

    /** The fallback when a name folds to nothing this can transliterate. */
    private static final String SLUG_FALLBACK = "user";

    private final UserRepository users;

    public UserAccounts(UserRepository users) {
        this.users = users;
    }

    /**
     * Creates an unverified account.
     *
     * <p>Callers are expected to have established that the address is free.
     * This does not check again, because the unique index is the check that
     * cannot lose a race — two simultaneous registrations both see a free
     * address, and one of them gets a constraint violation.
     */
    @Transactional
    public UserAccount register(EmailAddress email, String name, String locale, String currency) {
        User user = User.register(email, name, allocateSlug(name), locale, currency);
        return toAccount(users.save(user));
    }

    @Transactional(readOnly = true)
    public Optional<UserAccount> findByEmail(EmailAddress email) {
        return users.findByEmailAndDeletedAtIsNull(email).map(UserAccounts::toAccount);
    }

    @Transactional(readOnly = true)
    public Optional<UserAccount> findById(UUID id) {
        return users.findByIdAndDeletedAtIsNull(id).map(UserAccounts::toAccount);
    }

    /**
     * The account behind a public profile path — §10.2's {@code /v1/users/{slug}}.
     *
     * <p>Added by #90, which is the first thing outside this module to address an
     * account by anything other than its identifier: following is done from a
     * creator's page, and that page is reached by slug. A closed account is not
     * found, exactly as it is not by {@link #findById} — following somebody who
     * has left would be a subscription to nothing that the sender then has to
     * skip on every launch.
     */
    @Transactional(readOnly = true)
    public Optional<UserAccount> findBySlug(String slug) {
        if (slug == null || slug.isBlank()) {
            return Optional.empty();
        }
        return users.findBySlugAndDeletedAtIsNull(slug).map(UserAccounts::toAccount);
    }

    /** Whether an address is spoken for, including by a closed account. */
    @Transactional(readOnly = true)
    public boolean isEmailTaken(EmailAddress email) {
        return users.existsByEmail(email);
    }

    /**
     * Records that the address has been proven. Doing nothing on a second call
     * is deliberate: a user clicking a link twice should see a verified
     * account, not an error.
     */
    @Transactional
    public void markEmailVerified(UUID userId, Instant at) {
        users.findByIdAndDeletedAtIsNull(userId).ifPresent(user -> user.markEmailVerified(at));
    }

    /**
     * §4.1's A-12 (#277): moves the account to an address that has just proved itself.
     *
     * <p><strong>The uniqueness check is here and it is not the one that decides.</strong>
     * Two accounts asking to move to one address is a race this cannot win by reading —
     * both see it free — and the unique index on {@code users.email} is what refuses the
     * second. What the check buys is the ordinary case: somebody who typed an address
     * that is already registered is told so by the endpoint that took the confirmation,
     * rather than by a constraint violation surfacing as a 500.
     *
     * <p>Answers {@code false} rather than throwing when the address is taken, because
     * the caller has more to say about it than this module does: it holds the request
     * row, and it is the one that has to decide whether the link is spent.
     *
     * @return whether the address moved
     */
    @Transactional
    public boolean changeEmail(UUID userId, EmailAddress newEmail, Instant at) {
        Optional<User> account = users.findByIdAndDeletedAtIsNull(userId);
        if (account.isEmpty()) {
            return false;
        }
        if (account.get().getEmail().equals(newEmail)) {
            // Already there. Not a failure — a second click on the same link, or a
            // change that was confirmed and then asked for again — and answering true
            // is what makes the confirmation endpoint idempotent in the way that
            // matters: the account ends up at the address the link named.
            return true;
        }
        if (users.existsByEmail(newEmail)) {
            return false;
        }
        account.get().changeEmail(newEmail, at);
        return true;
    }

    private String allocateSlug(String name) {
        String base = Slugs.slugify(name);
        if (base.length() < 3) {
            // Too short for the column's constraint, and unhelpful in a URL. A
            // name in a script we do not transliterate lands here.
            base = SLUG_FALLBACK;
        }
        if (base.length() > SLUG_BASE_LIMIT) {
            base = trimTrailingHyphen(base.substring(0, SLUG_BASE_LIMIT));
        }

        if (!users.existsBySlug(base)) {
            return base;
        }
        for (int suffix = 2; suffix <= SLUG_ATTEMPTS; suffix++) {
            String candidate = base + "-" + suffix;
            if (!users.existsBySlug(candidate)) {
                return candidate;
            }
        }
        // Past that, stop asking. This is still checked by the unique index, so
        // the pathological case is a retry, not a duplicate.
        return base + "-" + Long.toString(Math.abs(UUID.randomUUID().getMostSignificantBits()), 36);
    }

    private static String trimTrailingHyphen(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '-') {
            end--;
        }
        return value.substring(0, end);
    }

    static UserAccount toAccount(User user) {
        return new UserAccount(
                user.getId(),
                user.getEmail(),
                user.getName(),
                user.getSlug(),
                user.isEmailVerified(),
                user.getDeletionScheduledAt(),
                user.getSuspendedAt(),
                user.getLocale());
    }
}
