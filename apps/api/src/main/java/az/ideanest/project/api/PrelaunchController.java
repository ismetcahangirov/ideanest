package az.ideanest.project.api;

import az.ideanest.project.ProjectProperties;
import az.ideanest.project.application.PrelaunchService;
import az.ideanest.shared.EmailAddress;
import az.ideanest.shared.ratelimit.RateLimitExceededException;
import az.ideanest.shared.ratelimit.RateLimiter;
import az.ideanest.shared.ratelimit.RateLimiter.RateLimitDecision;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The public half of a pre-launch page: what it shows, and how to be told when the
 * campaign opens.
 *
 * <p><strong>A separate controller because the audience is different.</strong>
 * Every endpoint on {@link ProjectController} requires a bearer token and refuses a
 * stranger with a 404; all three here are reachable by anybody, because the people
 * a pre-launch page collects are by definition people who have not registered. The
 * split is what keeps that difference visible: a public endpoint added to the
 * creator's controller would inherit its shape and its exception advice, and the
 * next reader would have to check the filter chain to find out which is which.
 *
 * <p><strong>The creator's transition lives on the other controller, at the same
 * path.</strong> {@code POST /v1/projects/{id}/prelaunch} opens the page and
 * requires the creator; {@code GET} on that path serves it and requires nothing.
 * {@code SecurityConfiguration} permits the {@code GET} and only the {@code GET},
 * which is the same distinction it already draws for {@code /v1/categories}.
 *
 * <p><strong>Rate limiting is here rather than in the service</strong>, following
 * {@code AuthController}: it is about the transport — a source address, a header, a
 * 429 with {@code Retry-After} — and the service should not have to know it is
 * being reached over HTTP.
 */
@RestController
@RequestMapping("/v1/projects/{id}")
public class PrelaunchController {

    /**
     * How long a pre-launch page may be served from a cache.
     *
     * <p>§10.3 asks for caching on public reads, and this response is a title, a
     * summary, and a cover image that change rarely — against a follower count that
     * changes constantly. A minute is the compromise: long enough that a link shared
     * on social media does not turn into a request per visitor, short enough that
     * the number a visitor sees is one they would recognise. Public rather than
     * private, because there is nothing in the body that belongs to the person
     * reading it.
     */
    private static final Duration PAGE_CACHE = Duration.ofMinutes(1);

    private final PrelaunchService prelaunch;
    private final RateLimiter rateLimiter;
    private final ProjectProperties properties;

    public PrelaunchController(
            PrelaunchService prelaunch, RateLimiter rateLimiter, ProjectProperties properties) {
        this.prelaunch = prelaunch;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
    }

    /**
     * The pre-launch page for a campaign that has one.
     *
     * <p>404 for everything else, including a campaign that exists and is still a
     * draft — the same answer {@code ProjectAccess} gives a stranger, so that this
     * endpoint cannot be used to find out what other people are preparing.
     */
    @GetMapping("/prelaunch")
    public ResponseEntity<PrelaunchPageResponse> page(@PathVariable UUID id) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(PAGE_CACHE).cachePublic())
                .body(PrelaunchPageResponse.of(prelaunch.page(id)));
    }

    /**
     * "Tell me when this opens." §10.2, and C-11 of §4.9.
     *
     * <p><strong>An unauthenticated write that promises to send mail</strong>, which
     * is the shape of an open relay if it is left unbounded — so it is limited twice,
     * exactly as registration is. Per source address, so one script cannot fill a
     * campaign's list; and per email address, separately, because the per-source
     * limit alone does not stop somebody with many sources from subscribing one
     * person to every campaign on the platform, and that is mail-bombing them with
     * our domain on the envelope.
     *
     * <p>Both limits are counted before anything is written, and the address limit
     * only when there is an address to count — a signed-in caller is already bounded
     * by having an account.
     *
     * @param accessToken present when the caller happens to be signed in, null
     *     otherwise. That is the whole difference between the two kinds of follower
     * @param request optional in full: a signed-in caller sends no body at all
     */
    @PostMapping("/remind")
    public RemindResponse remind(
            @AuthenticationPrincipal Jwt accessToken,
            @PathVariable UUID id,
            @RequestBody(required = false) RemindRequest request,
            HttpServletRequest httpRequest) {

        ProjectProperties.Reminders limits = properties.reminders();
        enforce(rateLimiter.recordAttempt(
                "remind:ip:" + clientAddressOf(httpRequest), limits.signupsPerClient(), limits.window()));

        UUID accountId = accountOf(accessToken);
        EmailAddress email = accountId == null ? emailOf(request) : null;

        if (email != null) {
            enforce(rateLimiter.recordAttempt(
                    "remind:email:" + email.value(), limits.signupsPerAddress(), limits.window()));
        }

        return RemindResponse.of(prelaunch.register(id, accountId, email));
    }

    /**
     * "Stop reminding me."
     *
     * <p>Two ways in, because there are two kinds of follower: a signed-in caller is
     * identified by their access token, and everybody else by the token in the link
     * the launch notice carried. 204 either way, including when there was nothing to
     * remove — see {@code PrelaunchService#withdraw} for why this endpoint must not
     * report whether it did anything.
     *
     * <p>No rate limit. Every other write here is bounded because it creates an
     * obligation; this one only ever removes one, and a limiter that could refuse an
     * unsubscribe would be a limiter that keeps people on a list against their word.
     */
    @DeleteMapping("/remind")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void forget(
            @AuthenticationPrincipal Jwt accessToken,
            @PathVariable UUID id,
            @RequestParam(name = "token", required = false) String token) {

        prelaunch.withdraw(id, accountOf(accessToken), token);
    }

    /**
     * The account making the request, or null when nobody is signed in.
     *
     * <p>Spring Security's anonymous authentication leaves the principal null on
     * these endpoints, which is exactly the signal this method turns into a value.
     * As everywhere else in this module, the caller's identity comes from our own
     * signature and never from a path or a body parameter.
     */
    private static UUID accountOf(Jwt accessToken) {
        return accessToken == null ? null : UUID.fromString(accessToken.getSubject());
    }

    /** The address in the body, or null when there was no body and no address. */
    private static EmailAddress emailOf(RemindRequest request) {
        if (request == null || request.email() == null || request.email().isBlank()) {
            return null;
        }
        // EmailAddress refuses what is not an address, and the module's advice
        // turns that into a 400. Normalising here rather than in the service means
        // the rate-limit key below is the same key for Person@Example.com and
        // person@example.com, which is the point of having one representation.
        return EmailAddress.of(request.email());
    }

    private static void enforce(RateLimitDecision decision) {
        if (!decision.allowed()) {
            throw new RateLimitExceededException(decision.retryAfter());
        }
    }

    /**
     * The remote address as the container saw it.
     *
     * <p>Deliberately not {@code X-Forwarded-For}, for the reason
     * {@code AuthController} gives: trusting that header without a proxy in front
     * means any client can pick its own rate-limit bucket by inventing one, which
     * turns the limiter off. Behind a load balancer the correct fix is
     * {@code server.forward-headers-strategy} (#139).
     */
    private static String clientAddressOf(HttpServletRequest request) {
        String address = request.getRemoteAddr();
        return address == null ? "unknown" : address;
    }
}
