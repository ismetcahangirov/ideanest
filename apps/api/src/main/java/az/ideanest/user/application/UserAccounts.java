package az.ideanest.user.application;

import az.ideanest.shared.EmailAddress;
import az.ideanest.shared.Slugs;
import az.ideanest.user.domain.User;
import az.ideanest.user.infrastructure.UserRepository;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
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
     * The accounts behind a set of identifiers, keyed by identifier.
     *
     * <p>The batch counterpart of {@link #findById}, added by #57 so that a page of
     * pledges can be resolved into a page of backers in one query rather than one
     * per row.
     *
     * <p><strong>A map, not a list</strong>, because every caller of this is about
     * to look each account up by the identifier it already holds — and a caller
     * given a list would build the same map, or would scan it and reintroduce the
     * N+1 this method exists to remove.
     *
     * <p><strong>An absent key is the answer, not an error.</strong> A deleted or
     * anonymised account is excluded by the repository, so an identifier that a
     * financial row still references quite legitimately resolves to nothing — that
     * is precisely what §17.4's anonymisation does, and a caller that must render
     * something anyway is the one that knows what.
     */
    @Transactional(readOnly = true)
    public Map<UUID, UserAccount> findAllById(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            // Spring Data would issue `IN ()`, which PostgreSQL parses and which
            // costs a round trip to learn what the caller already knows.
            return Map.of();
        }
        return users.findByIdInAndDeletedAtIsNull(ids).stream()
                .map(UserAccounts::toAccount)
                .collect(Collectors.toMap(UserAccount::id, Function.identity()));
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
