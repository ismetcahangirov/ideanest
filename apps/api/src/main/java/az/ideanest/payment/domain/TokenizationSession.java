package az.ideanest.payment.domain;

import java.net.URI;
import java.time.Instant;
import java.util.Objects;

/**
 * Where to send the backer so that the provider — and never this service — sees their
 * card.
 *
 * <p>The shape §17.2's SAQ A target forces: the platform hands over a redirect or a
 * hosted-fields session and gets back an identifier, and the card itself never
 * touches a server that would otherwise be in scope. {@link PaymentProvider} returns
 * this rather than a token because the answer is not known yet — the backer has a
 * 3-D Secure challenge to complete first, which is a human being and a bank in the
 * middle of the call.
 *
 * @param sessionId what {@link PaymentProvider#resolveTokenization} is later asked
 *     about
 * @param redirectUrl where the backer goes. <strong>{@code https} only</strong>, and
 *     refused here rather than at the browser: a card entry page reached over plain
 *     HTTP is the failure this whole arrangement exists to prevent
 * @param expiresAt when the provider stops honouring the session. Carried so that a
 *     checkout resumed an hour later is told to start again rather than sent to a
 *     page that will refuse it without saying why
 */
public record TokenizationSession(String sessionId, URI redirectUrl, Instant expiresAt) {

    public TokenizationSession {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("A tokenisation session that cannot be resolved is not one");
        }
        Objects.requireNonNull(redirectUrl, "A session sends the backer somewhere");
        Objects.requireNonNull(expiresAt, "A session that never expires is a session nobody can reason about");
        if (!"https".equalsIgnoreCase(redirectUrl.getScheme())) {
            throw new IllegalArgumentException("A card entry page is reached over https, and this one is " + redirectUrl);
        }
    }
}
