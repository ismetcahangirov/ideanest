package az.ideanest.obligation.api;

import az.ideanest.obligation.application.UpdateObligations;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
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
 * The moderator's queue of lapsed obligations — §4.11's AD-01, issue #437.
 *
 * <h2>A queue, because the alternative was the platform deciding</h2>
 *
 * <p>#437 is explicit that nothing automatic may touch money or a campaign's state: §9.7 says a
 * creator who cannot deliver "offers a refund; the platform mediates", and suspension is a
 * moderator decision under AD-02. So what a lapse produces is this — a case in front of a person —
 * and the strongest verb on this controller is "resolve", which records that somebody looked.
 *
 * <h2>Resolving closes the case and not the obligation</h2>
 *
 * <p>The clock keeps running, and a creator who lapses again is escalated again. That is why the
 * note is required: the next moderator has to be able to tell "spoke to them" from "they had
 * already posted".
 *
 * <p>{@code MODERATE_CONTENT}, checked in the service rather than by an annotation here, following
 * {@code FeeScheduleController}: the service is also where the decision is recorded, and an
 * authorised action nobody recorded and a recorded action nobody authorised are the same defect
 * from opposite ends.
 *
 * <p><strong>{@code no-store}</strong>, like every response under this prefix.
 */
@RestController
@RequestMapping("/v1/admin/update-obligations")
public class AdminObligationController {

    /** One screenful and then some. A queue that needs a cursor is a queue nobody is working. */
    private static final int DEFAULT_LIMIT = 100;

    private static final int MAX_LIMIT = 500;

    private final UpdateObligations obligations;

    public AdminObligationController(UpdateObligations obligations) {
        this.obligations = obligations;
    }

    /** Lapses nobody has closed, oldest first. */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ObligationResponses.Queue> queue(
            @AuthenticationPrincipal Jwt accessToken,
            @RequestParam(name = "limit", required = false) Integer limit) {

        int bounded = limit == null ? DEFAULT_LIMIT : Math.clamp(limit, 1, MAX_LIMIT);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ObligationResponses.Queue.of(obligations.escalated(callerOf(accessToken), bounded)));
    }

    /** Records that a moderator has seen this one, and what they did about it. */
    @PostMapping(path = "/{projectId}/resolve", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ObligationResponses.Escalation> resolve(
            @AuthenticationPrincipal Jwt accessToken,
            @PathVariable UUID projectId,
            @Valid @RequestBody ResolveRequest request) {

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ObligationResponses.Escalation.of(
                        obligations.resolve(callerOf(accessToken), projectId, request.note())));
    }

    /**
     * @param note required, and V68 says why: a resolution nobody explained is one nobody can rely
     *     on, and the next person to open this campaign's file has to be able to tell "spoke to
     *     them" from "they had already posted"
     */
    public record ResolveRequest(@NotBlank @Size(max = 2000) String note) {
    }

    private static UUID callerOf(Jwt accessToken) {
        return UUID.fromString(accessToken.getSubject());
    }
}
