package az.ideanest.project;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Campaign settings.
 *
 * @param moderation who is allowed to decide a submitted campaign's fate
 * @param story how much of a story's editing history is kept
 * @param collaborators how invitations to work on a campaign behave
 */
@ConfigurationProperties(prefix = "ideanest.project")
public record ProjectProperties(Moderation moderation, Story story, Collaborators collaborators) {

    public ProjectProperties {
        // A deployment that configures neither section still starts. Nested records
        // bind to null when the whole block is absent, and a null here would be a
        // NullPointerException at the first autosave or the first invitation rather
        // than a configuration error at start-up — which is the wrong end of the day
        // to find it.
        story = story == null ? Story.defaults() : story;
        collaborators = collaborators == null ? Collaborators.defaults() : collaborators;
    }

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
     * The story's version history.
     *
     * @param versionInterval how long after the newest version another one may be
     *     written. <strong>This is the number that decides whether the feature is
     *     usable or ruinous.</strong> The editor autosaves every few seconds while
     *     somebody types, so a version per save is thousands of {@code jsonb}
     *     documents for one afternoon's work — a history nobody can read and a
     *     table dominated by rows that differ by one word. Configuration rather
     *     than a literal because it is a judgement about how much work a creator
     *     may lose, and the answer for a staging environment being exercised by a
     *     test is not the answer for production
     * @param versionsKept how many versions survive per project, oldest pruned
     *     first. Fifty at the configured interval is a working day and a half of
     *     recoverable history, which is longer than anybody remembers what they
     *     changed
     */
    public record Story(Duration versionInterval, int versionsKept) {

        /** Contract §5: five minutes, and the last fifty. */
        private static final Duration DEFAULT_INTERVAL = Duration.ofMinutes(5);

        private static final int DEFAULT_VERSIONS_KEPT = 50;

        static Story defaults() {
            return new Story(DEFAULT_INTERVAL, DEFAULT_VERSIONS_KEPT);
        }

        public Story {
            // Binding leaves an omitted property at its zero value, so an
            // operator who configures the interval and not the count gets the
            // documented default rather than a history of nothing.
            versionInterval = versionInterval == null ? DEFAULT_INTERVAL : versionInterval;
            versionsKept = versionsKept == 0 ? DEFAULT_VERSIONS_KEPT : versionsKept;

            if (versionInterval.isNegative()) {
                throw new IllegalArgumentException("The story version interval cannot be negative");
            }
            if (versionsKept < 1) {
                // A negative count is a typo, and "keep no history" is not a
                // configuration of this feature — it is the feature being off, and
                // a version written and pruned in the same transaction is worse
                // than one never written. Refused at start-up, where an operator
                // sees it, rather than at the first autosave.
                throw new IllegalArgumentException("At least one story version has to be kept");
            }
        }
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

        /** What {@code application.yml} configures, so an absent block behaves the same. */
        private static final Duration DEFAULT_TTL = Duration.ofDays(7);

        static Collaborators defaults() {
            // Logging is off, which is the safe half of the pair: a developer who
            // needs the link turns it on in application-local.yml deliberately.
            return new Collaborators(DEFAULT_TTL, false);
        }

        public Collaborators {
            invitationTtl = invitationTtl == null ? DEFAULT_TTL : invitationTtl;

            if (!invitationTtl.isPositive()) {
                // Zero is every invitation expiring before the mail is read, which
                // reads to a creator as the feature being broken. An operator sees
                // this at start-up instead.
                throw new IllegalArgumentException("An invitation has to be valid for some length of time");
            }
        }
    }
}
