package az.ideanest.project;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Campaign settings.
 *
 * @param moderation who is allowed to decide a submitted campaign's fate
 * @param collaborators how invitations to work on a campaign behave
 */
@ConfigurationProperties(prefix = "ideanest.project")
public record ProjectProperties(Moderation moderation, Collaborators collaborators) {

    /**
     * @param moderatorEmails the accounts that may approve, reject, or send a
     *     campaign back, by verified address. <strong>Empty means nobody
     *     can</strong>, which is the default and the point: until epic #100
     *     brings a role model, this is the only thing standing between a
     *     submitted campaign and the creator approving it themselves, so it
     *     fails closed. Addresses rather than identifiers because an operator
     *     setting this knows the staff addresses and does not know their row
     *     identifiers, and the same reason {@code GOOGLE_CLIENT_IDS} is a
     *     comma-separated environment value: it is deployment configuration,
     *     which differs per environment and is not a secret.
     *     <p>The address is read from our own {@code users} row for the
     *     already-authenticated caller. Nothing here is taken from the request,
     *     which is the distinction {@code provider_identities} draws when it
     *     refuses to match an external identity on an address: the danger there
     *     is a third party choosing what address to claim, and there is no
     *     third party in this comparison.
     */
    public record Moderation(List<String> moderatorEmails) {
    }

    /**
     * @param invitationTtl how long an invitation link works. An invitation is not
     *     a standing offer: an address left unaccepted for a week is usually a typo
     *     or somebody who has left the company, and a link that never expires is
     *     edit access to a campaign sitting in an old mailbox. Long enough to
     *     survive a holiday, short enough that a forwarded message is not a
     *     permanent key — the same trade-off as
     *     {@code ideanest.auth.verification-token-ttl}, decided one way for a link
     *     somebody is waiting for and another for one they were not expecting.
     * @param logInvitationLinks whether to write invitation links to the log.
     *     <strong>Local development only</strong>, and false by default. There is
     *     no mail transport (#86), so without this a developer cannot accept an
     *     invitation at all; with it on anywhere else, anybody who can read logs can
     *     take over the editing of an unlaunched campaign. See
     *     {@code application-local.yml} and {@code LoggingVerificationNotifier},
     *     which makes the same trade in the same direction.
     */
    public record Collaborators(Duration invitationTtl, boolean logInvitationLinks) {
    }
}
