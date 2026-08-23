package az.ideanest.user.application;

import az.ideanest.user.domain.User;
import az.ideanest.user.infrastructure.ProfileLocations;
import az.ideanest.user.infrastructure.SocialLinkRepository;
import az.ideanest.user.infrastructure.UserRepository;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A copy of the account's own data, in a form a machine can read.
 *
 * <p><strong>What is left out, and why.</strong> Not the password hash: it is
 * not a fact about the person, it is a credential, and Argon2 or not, a hash of
 * a password most people reuse is the single most damaging field we hold. Not
 * the hash of any refresh or verification token, for the same reason with less
 * argument — each is a key to this account, and an export that contained them
 * would turn one leaked file into a permanent takeover. An export is a copy of
 * what we know about someone, not a copy of their keys.
 *
 * <p>The rest of the security history <em>is</em> included, because it is the
 * part a person actually needs: which devices signed in, from where, and when a
 * session ended. That is the record that answers "was somebody else in my
 * account", and withholding it in the name of security would protect nobody.
 *
 * <p>What is not here yet is everything that does not exist yet — pledges,
 * addresses, messages, notification preferences. Each joins this document with
 * the feature that creates it. An export that silently omits a category is
 * worse than one that has not got there, so {@code format} is versioned.
 */
@Service
public class AccountExportService {

    private final UserRepository users;
    private final SocialLinkRepository socialLinks;
    private final ProfileLocations locations;
    private final AccountSecurity security;
    private final Clock clock;

    public AccountExportService(
            UserRepository users,
            SocialLinkRepository socialLinks,
            ProfileLocations locations,
            AccountSecurity security,
            Clock clock) {
        this.users = users;
        this.socialLinks = socialLinks;
        this.locations = locations;
        this.security = security;
        this.clock = clock;
    }

    /** Empty when there is no such live account. */
    @Transactional(readOnly = true)
    public Optional<AccountExport> exportFor(UUID userId) {
        return users.findByIdAndDeletedAtIsNull(userId).map(this::toExport);
    }

    private AccountExport toExport(User user) {
        AccountSecurity.SecurityHistory history = security.historyFor(user.getId());

        return new AccountExport(
                AccountExport.FORMAT,
                clock.instant(),
                new AccountExport.Account(
                        user.getId(),
                        user.getEmail().value(),
                        user.getName(),
                        user.getSlug(),
                        user.getBio(),
                        user.getAvatarUrl(),
                        user.getWebsiteUrl(),
                        // The slug rather than the identifier: a uuid means nothing in a file
                        // somebody opens two years later, and the slug is the value that still
                        // resolves if the name is ever retranslated.
                        locations.findById(user.getLocationId())
                                .map(ProfileLocation::slug)
                                .orElse(null),
                        socialLinksOf(user.getId()),
                        user.getLocale(),
                        user.getCurrency(),
                        user.getEmailVerifiedAt(),
                        user.getCreatedAt(),
                        user.getUpdatedAt(),
                        user.getDeletionRequestedAt(),
                        user.getDeletionScheduledAt()),
                history.sessions(),
                history.verifications());
    }

    /**
     * §4.2's P-03, in the export's own shape.
     *
     * <p>The order is the stored order, because the order is something the person chose and an
     * export that reordered it would be reporting a fact about our storage rather than about
     * them.
     */
    private List<AccountExport.SocialLink> socialLinksOf(UUID userId) {
        return socialLinks.findByUserIdOrderByPositionAsc(userId).stream()
                .map(link -> new AccountExport.SocialLink(link.getPlatform().name(), link.getUrl()))
                .toList();
    }
}
