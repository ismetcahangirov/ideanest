package az.ideanest.project;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Campaign settings.
 *
 * @param moderation who is allowed to decide a submitted campaign's fate
 */
@ConfigurationProperties(prefix = "ideanest.project")
public record ProjectProperties(Moderation moderation) {

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
}
