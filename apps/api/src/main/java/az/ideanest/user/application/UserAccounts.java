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
                user.getDeletionScheduledAt());
    }
}
