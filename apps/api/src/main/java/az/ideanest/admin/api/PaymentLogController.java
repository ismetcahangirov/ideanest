package az.ideanest.admin.api;

import az.ideanest.admin.AdminConsoleProperties;
import az.ideanest.admin.application.ConsoleReadService;
import az.ideanest.payment.application.PaymentLogScope;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * §4.11's AD-05 over HTTP (#304): every charge, its provider reference, and its state
 * history.
 *
 * <p><strong>"State history" is a list of rows and not a field on one.</strong> §7.2 makes
 * {@code transactions} append-only, so a call that was pending and later succeeded is two
 * rows sharing an idempotency key rather than one row that changed. A screen asking for a
 * charge's history is therefore asking for a pledge's transactions — which is what
 * {@code pledgeId} is for, and why it is the narrowest filter this endpoint offers.
 *
 * <p><strong>Read only, and it could not be otherwise.</strong> V41 puts a statement-level
 * trigger on the table that raises on UPDATE and DELETE. There is no correcting a row from
 * here, and a correction is a new row — which is the property that makes this log worth
 * reading at all.
 *
 * <p>{@code no-store} and staff-only for the reasons {@link AuditTrailController} gives.
 */
@RestController
@RequestMapping("/v1/admin/payments")
public class PaymentLogController {

    private final ConsoleReadService console;
    private final AdminConsoleProperties properties;

    public PaymentLogController(ConsoleReadService console, AdminConsoleProperties properties) {
        this.console = console;
        this.properties = properties;
    }

    /**
     * One page of the log, newest first.
     *
     * @param pledgeId one pledge's whole attempt history — every decline and the collection
     *     that eventually succeeded. The question §9.6's retry schedule is argued from
     * @param projectId everything that moved on one campaign. Ignored when a pledge is
     *     named: the pledge is the narrower question, the two indexes do not combine, and
     *     the response echoes what was actually applied
     * @param after the {@code nextCursor} of the previous page, or absent for the first
     * @param limit clamped to {@code ideanest.admin.payments.max-page-size}
     */
    @GetMapping
    public ResponseEntity<PaymentLogResponses.Page> log(
            @AuthenticationPrincipal Jwt accessToken,
            @RequestParam(required = false) UUID pledgeId,
            @RequestParam(required = false) UUID projectId,
            @RequestParam(required = false) UUID after,
            @RequestParam(required = false) Integer limit) {

        PaymentLogScope scope = new PaymentLogScope(pledgeId, projectId);

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(PaymentLogResponses.of(console.paymentLog(
                        staffOf(accessToken), scope, after, properties.payments().effective(limit))));
    }

    /** Whoever is signed in. See {@link AuditTrailController} on why the token and not the body. */
    private static UUID staffOf(Jwt accessToken) {
        return UUID.fromString(accessToken.getSubject());
    }
}
