package az.ideanest.auth.application;

import az.ideanest.auth.application.OidcIdentityVerifier.VerifiedIdentity;
import az.ideanest.auth.domain.IdentityProvider;
import az.ideanest.auth.domain.ProviderIdentity;
import az.ideanest.auth.infrastructure.ProviderIdentityRepository;
import az.ideanest.auth.infrastructure.TwoFactorSecretRepository;
import az.ideanest.shared.EmailAddress;
import az.ideanest.user.application.UserAccount;
import az.ideanest.user.application.UserAccounts;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Signing in with Google or Apple.
 *
 * <p>The client obtains an ID token from the provider and sends it here; this
 * class decides which account that token belongs to, and hands the answer to the
 * same {@link SessionStarter} a password sign-in uses. A provider sign-in and a
 * password sign-in produce the same session, the same rotation, and the same
 * fifteen-minute access token, because they differ only in how the person was
 * identified.
 *
 * <h2>Which account a token opens</h2>
 *
 * <p><strong>A known {@code (provider, subject)} pair is the account.</strong>
 * Nothing else is consulted — not the address, which the provider allows the
 * person to change, and not the name.
 *
 * <p><strong>An address the provider has not verified is worth nothing.</strong>
 * It creates no account and links to none. The claim in that case is "somebody
 * typed this address into their provider account", and treating that as proof is
 * the standard way to take over somebody else's account with a provider sign-in
 * button. Both Google and Apple verify in practice; the check is here for the
 * day one of them does not, or for a provider added later that does not.
 *
 * <p><strong>A verified address that matches a verified account here is linked
 * automatically.</strong> Both sides have proven the same address, so both are
 * the same person, and refusing would leave a user staring at a sign-in button
 * that does nothing.
 *
 * <p><strong>A verified address that matches an <em>unverified</em> account here
 * is refused.</strong> This is the case that looks safe and is not. Registration
 * creates an account before the address is proven, so anybody can register
 * {@code victim@example.com}, choose the password, and wait; if the victim's
 * later Google sign-in linked to that account, the attacker would keep a password
 * to an account that now belongs to somebody else. It is a pre-registration
 * attack, and the condition that defeats it is that the local account must have
 * proven the address too. The user's way out is the verification email already
 * sitting in the inbox they have just proven they control.
 *
 * <p><strong>No account with that address at all: one is created</strong>,
 * through {@code UserAccounts}, with the address already verified — the provider
 * proved it, and asking the user to prove it again would be asking them to prove
 * something we were just told by an authority we trust for the whole sign-in.
 * That account has no password: {@code user_credentials} simply has no row,
 * which is what that table was shaped for.
 */
@Service
public class SocialSignInService {

    private static final Logger log = LoggerFactory.getLogger(SocialSignInService.class);

    /**
     * The same refusal as every other authentication failure. An address the
     * provider did not verify tells us nothing about who is calling, so the
     * answer must tell them nothing about who has an account here.
     */
    private static final String REFUSAL = "That sign-in could not be verified. Try again.";

    /** The column allows eighty characters, and a display name is not an essay. */
    private static final int MAX_NAME_LENGTH = 80;

    private final OidcIdentityVerifier verifier;
    private final ProviderIdentityRepository identities;
    private final UserAccounts users;
    private final SessionStarter sessionStarter;
    private final TwoFactorSecretRepository twoFactorSecrets;
    private final TwoFactorChallenges challenges;
    private final Clock clock;

    public SocialSignInService(
            OidcIdentityVerifier verifier,
            ProviderIdentityRepository identities,
            UserAccounts users,
            SessionStarter sessionStarter,
            TwoFactorSecretRepository twoFactorSecrets,
            TwoFactorChallenges challenges,
            Clock clock) {
        this.verifier = verifier;
        this.identities = identities;
        this.users = users;
        this.sessionStarter = sessionStarter;
        this.twoFactorSecrets = twoFactorSecrets;
        this.challenges = challenges;
        this.clock = clock;
    }

    /**
     * @param provider which provider the token came from
     * @param idToken the token the client obtained. Everything about the person
     *     is read out of it, after verification
     * @param nonce the nonce the client bound its authorisation request to
     * @param name a display name the client may pass on at first sign-in.
     *     <strong>Apple sends the name once</strong> — in the body of the first
     *     authorisation response, never in the token and never again — so a
     *     client that does not forward it at that moment has lost it. Used only
     *     when an account is created, and never to change an existing one: it is
     *     client-supplied, and a value the client controls must not be able to
     *     rewrite an established profile
     * @param deviceLabel how the session will appear in the user's device list
     * @param userAgent recorded on the session for the same reason
     * @param ipAddress recorded so that an unexpected session can be recognised
     */
    public record SocialSignInCommand(
            IdentityProvider provider,
            String idToken,
            String nonce,
            String name,
            String locale,
            String deviceLabel,
            String userAgent,
            String ipAddress) {
    }

    @Transactional
    public SignInOutcome signIn(SocialSignInCommand command) {
        Instant now = clock.instant();

        VerifiedIdentity identity = verifier.verify(command.provider(), command.idToken(), command.nonce());

        Optional<ProviderIdentity> known =
                identities.findByProviderAndSubject(identity.provider(), identity.subject());
        if (known.isPresent()) {
            return signInAsKnownIdentity(known.get(), identity, command, now);
        }

        // From here on the address decides what may happen, so it has to be one
        // the provider says it has proven.
        if (identity.email() == null || !identity.emailVerified()) {
            log.info(
                    "Refused a {} sign-in: the provider asserted {} address",
                    identity.provider().key(),
                    identity.email() == null ? "no" : "an unverified");
            throw new AuthenticationFailedException(REFUSAL);
        }

        Optional<UserAccount> existing = users.findByEmail(identity.email());
        return existing.isPresent()
                ? linkToExistingAccount(existing.get(), identity, command, now)
                : createAccount(identity, command, now);
    }

    private SignInOutcome signInAsKnownIdentity(
            ProviderIdentity link, VerifiedIdentity identity, SocialSignInCommand command, Instant now) {

        link.recordAuthentication(identity.email(), identity.emailVerified(), identity.privateEmail(), now);
        identities.save(link);

        UserAccount account = users.findById(link.getUserId())
                // The account was deleted, or the row is orphaned. Either way
                // there is nobody to sign in as.
                .orElseThrow(() -> new AuthenticationFailedException(REFUSAL));

        return startOrChallenge(
                account.id(),
                new AccessTokenIssuer.AccountStanding(account.emailVerified(), account.deletionPending()),
                command,
                now);
    }

    private SignInOutcome linkToExistingAccount(
            UserAccount account, VerifiedIdentity identity, SocialSignInCommand command, Instant now) {

        if (!account.emailVerified()) {
            // The pre-registration attack. See the class comment: the account
            // here has never proven this address, so linking would hand a
            // provider account to whoever registered first.
            throw new AccountLinkRefusedException(
                    "An account with this address exists here and its address has not been verified yet."
                            + " Open the verification email we sent, or sign in with your password,"
                            + " and then this provider can be linked.");
        }

        link(account, identity, now);
        return startOrChallenge(
                account.id(),
                // The address is proven either way by this point; the deletion
                // state is the account's own and is read rather than assumed.
                new AccessTokenIssuer.AccountStanding(true, account.deletionPending()),
                command,
                now);
    }

    private SignInOutcome createAccount(VerifiedIdentity identity, SocialSignInCommand command, Instant now) {
        UserAccount account = users.register(
                identity.email(), displayName(identity, command), command.locale(), "AZN");

        // The provider proved the address. Sending a verification email for one
        // that has just been proven by the authority we trusted for the entire
        // sign-in would be ceremony, and it would leave the account unable to
        // pledge until somebody opened it.
        users.markEmailVerified(account.id(), now);

        link(account, identity, now);

        // A brand new account cannot have a second factor or a pending
        // deletion, but it goes through the same door so that there is one
        // place where that is decided.
        return startOrChallenge(
                account.id(), new AccessTokenIssuer.AccountStanding(true, false), command, now);
    }

    private void link(UserAccount account, VerifiedIdentity identity, Instant now) {
        identities.findByUserIdAndProvider(account.id(), identity.provider()).ifPresent(other -> {
            // One person, one account per provider. Reaching here means a second
            // Google account claiming an address this one already proved, which
            // is a support question rather than something to resolve silently.
            throw new AccountLinkRefusedException("This account is already linked to a different "
                    + identity.provider().key() + " account. Sign in with that one, or contact support.");
        });

        ProviderIdentity link =
                ProviderIdentity.link(account.id(), identity.provider(), identity.subject(), now);
        link.recordAuthentication(identity.email(), identity.emailVerified(), identity.privateEmail(), now);

        try {
            identities.save(link);
        } catch (DataIntegrityViolationException e) {
            // Two first sign-ins for the same provider account at once: both saw
            // no link and the unique index refused the second. Retrying succeeds,
            // and inventing a second account for one person would not.
            log.info("A {} sign-in lost a race on the identity unique index", identity.provider().key());
            throw new AccountLinkRefusedException("That sign-in was already being processed. Try again.");
        }
    }

    /**
     * What to call somebody whose name we may not have been given.
     *
     * <p>The provider's own claim first, then whatever the client passed on —
     * which is the only way an Apple name ever arrives — then the local part of
     * the address, which is at least the person's own choice of string. The slug
     * allocator handles the rest.
     */
    private static String displayName(VerifiedIdentity identity, SocialSignInCommand command) {
        String candidate = firstNonBlank(identity.name(), command.name());
        if (candidate == null) {
            EmailAddress email = identity.email();
            candidate = email.value().substring(0, email.value().indexOf('@'));
        }
        candidate = candidate.trim();
        return candidate.length() > MAX_NAME_LENGTH ? candidate.substring(0, MAX_NAME_LENGTH).trim() : candidate;
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second != null && !second.isBlank() ? second : null;
    }

    /**
     * A session, unless the account has a second factor.
     *
     * <p>Two-factor applies here exactly as it does to a password. A provider
     * proves which Google account is calling; it says nothing about the second
     * factor this user enrolled, and letting the provider button skip it would
     * make two-factor advisory — which is the same as not having it, since an
     * attacker who reached the account through Google is precisely who it was
     * turned on for.
     *
     * <p>The enrolment must be confirmed, not merely started: somebody who
     * scanned a QR code and never entered a code has not proved they can, and
     * demanding one from them would be a lockout.
     */
    private SignInOutcome startOrChallenge(
            java.util.UUID userId,
            AccessTokenIssuer.AccountStanding standing,
            SocialSignInCommand command,
            Instant now) {

        if (twoFactorSecrets.findByUserIdAndConfirmedAtIsNotNull(userId).isPresent()) {
            return challenges.issue(
                    userId, command.deviceLabel(), command.userAgent(), command.ipAddress(), now);
        }

        return new SignInOutcome.Authenticated(sessionStarter.start(new SessionStarter.NewSession(
                userId,
                standing,
                command.deviceLabel(),
                command.userAgent(),
                command.ipAddress(),
                // A provider sign-in proves one factor. Recording otherwise
                // would let a payout claim a second factor nobody entered.
                false)));
    }
}
