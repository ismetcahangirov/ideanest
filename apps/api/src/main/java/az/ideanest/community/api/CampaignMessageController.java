package az.ideanest.community.api;

import az.ideanest.community.CommunityProperties;
import az.ideanest.community.application.CampaignMessageService;
import az.ideanest.community.application.SignalCursor;
import az.ideanest.shared.ratelimit.RateLimiter;
import az.ideanest.shared.ratelimit.RateLimits;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * §4.7's CD-13 over HTTP: {@code POST /v1/projects/{id}/messages} and the log of what has been
 * sent.
 *
 * <p><strong>{@code messages} rather than {@code broadcasts} or {@code emails}.</strong> The
 * first would name the mechanism rather than the act, and the delivery is per-recipient
 * notifications resolved against each person's preferences — some of which are in-app only. The
 * second would name one channel of three.
 *
 * <p>Both endpoints require a bearer token by falling through to
 * {@code SecurityConfiguration}'s catch-all. Which capability is decided in
 * {@code CampaignMessageService}, and it is not the same for both audiences — see that class.
 *
 * <p><strong>Rate limiting is here rather than in the service</strong>, following
 * {@code CommentController}: it is about the transport. The budget is per <em>campaign</em>
 * rather than per account, which {@code CommunityProperties.Messages} argues at length — the
 * harm is to the campaign's backers, so a campaign with four collaborators must not get four
 * times the allowance for reaching the same people.
 *
 * <p><strong>Spent by sending and not by reading.</strong> A creator scrolling their own sent
 * messages to work out what went wrong is exactly who a limiter must not stop.
 */
@RestController
@RequestMapping("/v1/projects/{projectId}/messages")
public class CampaignMessageController {

    private final CampaignMessageService messages;
    private final RateLimiter rateLimiter;
    private final CommunityProperties properties;

    public CampaignMessageController(
            CampaignMessageService messages, RateLimiter rateLimiter, CommunityProperties properties) {
        this.messages = messages;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
    }

    /**
     * Sends a message to the campaign's backers, or to a saved segment of them.
     *
     * <p>201 with the message, including {@code recipientCount} and {@code truncated}: what a
     * creator most needs back from this call is how many people it just reached, and finding out
     * by loading the list afterwards would make the number look like a separate fact from the
     * send.
     *
     * <p><strong>The rate limit is counted before the send and is not refunded.</strong> A
     * message refused by validation still costs a slot, which is deliberate: the budget exists
     * to bound how often this endpoint is reached at all, and a limiter that only counted
     * successes is one a script can spin against for free.
     */
    @PostMapping
    public ResponseEntity<CampaignMessageResponse> send(
            @AuthenticationPrincipal Jwt accessToken,
            @PathVariable UUID projectId,
            @Valid @RequestBody SendMessageRequest request) {

        CommunityProperties.Messages limits = properties.messages();
        RateLimits.enforce(
                rateLimiter.recordAttempt("message:project:" + projectId, limits.perProject(), limits.window()));

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(CampaignMessageResponse.of(messages.send(
                        projectId, callerOf(accessToken), request.segmentId(), request.subject(), request.body())));
    }

    /**
     * What this campaign has sent, newest first.
     *
     * <p>{@code no-store}, as every read behind a capability is: the body carries the campaign's
     * unpublished correspondence and the recipient counts behind it, and a shared cache holding
     * either is the failure that read is guarded against.
     */
    @GetMapping
    public ResponseEntity<CampaignMessageListResponse> sent(
            @AuthenticationPrincipal Jwt accessToken,
            @PathVariable UUID projectId,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "size", required = false) Integer size) {

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(CampaignMessageListResponse.of(
                        messages.sent(projectId, callerOf(accessToken), SignalCursor.decode(cursor), size)));
    }

    /** The account making the request, from our own signature and never from the body. */
    private static UUID callerOf(Jwt accessToken) {
        return UUID.fromString(accessToken.getSubject());
    }
}
