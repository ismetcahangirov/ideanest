package az.ideanest.payment.api;

import az.ideanest.payment.application.UnconfiguredProviderException;
import az.ideanest.payment.domain.UnknownProviderException;
import az.ideanest.payment.domain.WebhookVerificationException;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The webhook endpoint's refusals, as RFC 9457 problem details.
 *
 * <p>Scoped to {@link ProviderWebhookController} rather than applied globally, for
 * {@code PledgeExceptionHandler}'s reason: an advice that catches a broad type across
 * the service turns a bug somewhere else into a tidy 4xx and hides it.
 *
 * <h2>The bodies say less than everywhere else, deliberately</h2>
 *
 * <p>Every other handler in this service puts the useful specifics in {@code meta},
 * because the caller is a client the platform authenticated and helping it is the whole
 * point. <strong>Here the caller is unauthenticated by construction</strong>, and the
 * useful specifics are exactly what an attacker probing the endpoint wants: whether the
 * signature or the timestamp was wrong, which providers the platform has adapters for,
 * how wide the replay window is. So the responses are deliberately uninformative, and
 * the detail goes to the log where the operator is.
 *
 * <p><strong>Nothing here answers 5xx.</strong> An exception that is not one of these
 * three falls through to Spring's default 500 — and that is correct rather than an
 * omission: a handler that failed must produce a status the provider retries, and every
 * provider in §9.3 retries a 500. Converting an unexpected failure into a 200 would
 * discard a delivery nobody would ever send again.
 */
@RestControllerAdvice(assignableTypes = ProviderWebhookController.class)
public class ProviderWebhookExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ProviderWebhookExceptionHandler.class);

    /**
     * 404 for a path segment that names no provider in §9.3's list.
     *
     * <p>A 404 rather than a 400, because from the sender's point of view the resource
     * is the endpoint for a particular provider and there is no such endpoint. It is also
     * the same answer {@link #handleUnconfigured} gives, which means the endpoint reveals
     * nothing about which providers exist and which are merely not deployed.
     */
    @ExceptionHandler(UnknownProviderException.class)
    public ProblemDetail handleUnknownProvider(UnknownProviderException exception) {
        log.warn("A webhook arrived for '{}', which names no provider.", exception.offered());
        return notFound();
    }

    /**
     * 404 for a provider with no adapter deployed — which today is all of them.
     *
     * <p>Not a 503. A 503 invites a provider to retry and there is nothing to retry into:
     * a delivery for an unconfigured provider will still be unconfigured in an hour, and
     * the retries would run until the provider's own window expired.
     */
    @ExceptionHandler(UnconfiguredProviderException.class)
    public ProblemDetail handleUnconfigured(UnconfiguredProviderException exception) {
        log.warn("A webhook arrived for {}, which has no adapter deployed.", exception.provider());
        return notFound();
    }

    /**
     * 400 for a delivery that failed §17.2's checks.
     *
     * <p><strong>One status and one body for all three failures</strong> — a bad
     * signature, an unreadable body, and a timestamp outside the tolerance. Telling them
     * apart would be an oracle: a sender that could distinguish "wrong signature" from
     * "stale timestamp" learns which half of a forgery attempt is working.
     *
     * <p>A 400 rather than a 401 or a 403, because there is no authentication scheme to
     * challenge and no {@code WWW-Authenticate} header that would mean anything. The body
     * was not acceptable; that is a 400.
     *
     * <p>A 4xx rather than a 5xx matters for a second reason: it tells the provider not
     * to retry. A delivery whose signature does not verify will not verify on the fourth
     * attempt either, and a retry loop against it is a provider spending its retry budget
     * on something that cannot succeed.
     */
    @ExceptionHandler(WebhookVerificationException.class)
    public ProblemDetail handleVerificationFailure(WebhookVerificationException exception) {
        // ERROR and not WARN: either somebody is probing the endpoint or a signing secret
        // has drifted, and the second of those means the platform is silently discarding
        // real deliveries about real money.
        log.error("A webhook from {} failed verification: {}", exception.provider(), exception.getMessage());

        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setType(URI.create("https://ideanest.az/problems/webhook-rejected"));
        problem.setTitle("Delivery rejected");
        problem.setDetail("The delivery could not be accepted.");
        problem.setProperty("code", "WEBHOOK_REJECTED");
        return problem;
    }

    /** The one 404 body, shared so that the two reasons for it cannot be told apart. */
    private static ProblemDetail notFound() {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setType(URI.create("https://ideanest.az/problems/webhook-endpoint-not-found"));
        problem.setTitle("No such webhook endpoint");
        problem.setDetail("There is no webhook endpoint for that provider.");
        problem.setProperty("code", "WEBHOOK_ENDPOINT_NOT_FOUND");
        return problem;
    }
}
