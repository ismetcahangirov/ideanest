package az.ideanest.user.application;

import az.ideanest.user.UserProperties;
import az.ideanest.user.domain.User;
import az.ideanest.user.infrastructure.UserRepository;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The accounts, as administration needs to see and change them — §4.11's AD-04 (#104).
 *
 * <p><strong>Here rather than in the admin module, and that is not filing.</strong>
 * {@code users} is this module's table and {@code ModuleBoundaryTests} keeps the entity
 * and its repository inside it. What the admin module gets is this: a search that
 * answers {@link AdministeredAccount}s, and two writes that move one column each.
 *
 * <p><strong>Beside {@link UserAccounts} rather than inside it.</strong> That class is
 * the front door every module uses to look one account up, and its answers are
 * deliberately small. This one answers with an account's email address, its verification
 * status, and whether it has been stopped — a shape that exists for one screen, is read
 * by staff, and is audited by its caller. Two different contracts with two different
 * audiences, and the day the second one gains a field the first should not.
 *
 * <p><strong>Nothing here checks who is asking.</strong> The staff check belongs to the
 * admin module, which is the one that has a {@code PlatformStaff} and an audit row; a
 * second, weaker copy of it in the module that owns the table is how one of the two
 * eventually stops being applied.
 */
@Service
public class UserDirectory {

    private final UserRepository users;
    private final UserProperties properties;
    private final Clock clock;

    public UserDirectory(UserRepository users, UserProperties properties, Clock clock) {
        this.users = users;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Accounts matching a search, newest first.
     *
     * @param term what staff typed, matched against address, name and slug. Blank and
     *     null are the same thing — the unfiltered list a moderator opens
     * @param suspendedOnly the "who is stopped" filter, which V40's partial index serves
     * @param after the last identifier of the previous page, or null for the first
     * @param limit how many, bounded by
     *     {@link UserProperties.Administration#maxPageSize()} — an endpoint returning
     *     other people's addresses must not be able to return all of them at once
     */
    @Transactional(readOnly = true)
    public List<AdministeredAccount> search(String term, boolean suspendedOnly, UUID after, Integer limit) {
        int size = pageSize(limit);
        return users.search(patternOf(term), suspendedOnly, after, PageRequest.ofSize(size)).stream()
                .map(UserDirectory::toAdministered)
                .toList();
    }

    /** One account, as an administrator sees it. Empty for one that does not exist or is deleted. */
    @Transactional(readOnly = true)
    public Optional<AdministeredAccount> find(UUID userId) {
        return users.findByIdAndDeletedAtIsNull(userId).map(UserDirectory::toAdministered);
    }

    /**
     * §4.11's AD-04: stops an account.
     *
     * <p>The row is loaded for update, because two moderators acting on the same account
     * in the same minute is exactly what a queue produces — and the second one must find
     * the first one's decision rather than overwrite it. {@link User#suspend} keeps the
     * first, so this is idempotent: the account stays suspended under the reason and the
     * author it was suspended with.
     *
     * @throws AccountNotFoundException when there is no such account, or it has been
     *     deleted — a deleted account has nothing left to stop
     * @throws IllegalArgumentException when staff try to suspend themselves, which
     *     {@code users_suspension_has_another_author} refuses anyway
     */
    @Transactional
    public AdministeredAccount suspend(UUID userId, UUID by, String reason) {
        if (userId.equals(by)) {
            throw new IllegalArgumentException("An account cannot suspend itself");
        }
        User user = forUpdate(userId);
        user.suspend(clock.instant().truncatedTo(ChronoUnit.MICROS), by, reason.trim());
        return toAdministered(user);
    }

    /**
     * Lets an account back in.
     *
     * <p>Reversible where a campaign's suspension is not, and {@link User#suspend} says
     * why: an account has no funding window to restart, and a ban made in error has to be
     * undoable. Doing it twice is a no-op.
     */
    @Transactional
    public AdministeredAccount reinstate(UUID userId) {
        User user = forUpdate(userId);
        user.reinstate();
        return toAdministered(user);
    }

    private User forUpdate(UUID userId) {
        User user = users.findByIdForUpdate(userId).orElseThrow(() -> new AccountNotFoundException(userId));
        if (user.isDeleted()) {
            throw new AccountNotFoundException(userId);
        }
        return user;
    }

    private int pageSize(Integer limit) {
        UserProperties.Administration administration = properties.administration();
        if (limit == null || limit < 1) {
            return administration.defaultPageSize();
        }
        return Math.min(limit, administration.maxPageSize());
    }

    /**
     * The term as a {@code LIKE} pattern, folded.
     *
     * <p>Wrapped in wildcards at both ends, which is a scan and is the right cost here:
     * the search is made by a handful of staff accounts against a table read by an index
     * on every other path, and a prefix-only match would fail on the one thing staff most
     * often hold — a domain out of an email address.
     *
     * <p>The wildcards a caller typed are escaped, so a search for {@code 100%} is a
     * search for {@code 100%} rather than a match on everything.
     */
    private static String patternOf(String term) {
        if (term == null || term.isBlank()) {
            return null;
        }
        String escaped = term.trim()
                .toLowerCase(Locale.ROOT)
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_");
        return "%" + escaped + "%";
    }

    private static AdministeredAccount toAdministered(User user) {
        return new AdministeredAccount(
                user.getId(),
                user.getEmail(),
                user.getName(),
                user.getSlug(),
                user.getEmailVerifiedAt(),
                user.getSuspendedAt(),
                user.getSuspendedBy(),
                user.getSuspensionReason(),
                user.getDeletionScheduledAt(),
                user.getCreatedAt());
    }
}
