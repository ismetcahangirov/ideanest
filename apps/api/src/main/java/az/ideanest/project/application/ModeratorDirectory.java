package az.ideanest.project.application;

import az.ideanest.project.ProjectProperties;
import az.ideanest.shared.EmailAddress;
import az.ideanest.user.application.UserAccounts;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Who counts as platform staff, until there is a role model.
 *
 * <p><strong>This is deployment configuration, not a feature.</strong> Epic #100
 * owns administrative roles, the moderation queue, and the audit surface around
 * them. What this class exists to prevent in the meantime is the alternative:
 * moderation endpoints that require nothing beyond a valid access token, which
 * would let any creator approve their own campaign and would make "state
 * transitions are enforced server-side and cannot be bypassed" untrue on the day
 * it was written.
 *
 * <p><strong>It fails closed.</strong> An unset list means no account can
 * moderate anything, so a deployment that forgets to configure it has a
 * moderation queue nobody can clear — visible, and fixable with an environment
 * variable. The opposite default has no symptom at all until a campaign that
 * should not exist is live.
 *
 * <p>Ownership is deliberately not used as a stand-in. "The creator may not
 * approve their own campaign" reads like authorisation and is not: a second free
 * account approves the first one's work, and the rule would then have to be
 * found and removed rather than filled in.
 *
 * <p>The address is looked up from the {@code users} row for an already
 * authenticated caller, never read from the token or the request body. A list of
 * addresses is checked because that is what an operator can write down; the
 * account identifier is a value they would have to go and query for.
 */
@Service
public class ModeratorDirectory {

    private final Set<EmailAddress> moderators;
    private final UserAccounts accounts;

    public ModeratorDirectory(ProjectProperties properties, UserAccounts accounts) {
        this.accounts = accounts;
        // Normalised through EmailAddress at start-up rather than per call, so a
        // malformed entry stops the process with the value in the message instead
        // of silently never matching anybody.
        this.moderators = properties.moderation().moderatorEmails().stream()
                .map(String::trim)
                .filter(entry -> !entry.isEmpty())
                .map(EmailAddress::of)
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Whether this account may take a moderation decision.
     *
     * <p>A deleted account is not one: {@code findById} excludes soft-deleted
     * rows, so an access token minted before the account was closed stops being
     * enough here as soon as it is.
     */
    public boolean isModerator(UUID accountId) {
        if (moderators.isEmpty()) {
            // No query when there is no list. Not an optimisation — it keeps the
            // fail-closed case from depending on whether the account loads.
            return false;
        }
        return accounts.findById(accountId)
                .map(account -> moderators.contains(account.email()))
                .orElse(false);
    }
}
