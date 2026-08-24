package az.ideanest.admin.api;

import az.ideanest.admin.AdminConsoleProperties;
import az.ideanest.admin.application.ConsoleReadService;
import az.ideanest.ledger.application.LedgerAccount;
import az.ideanest.ledger.application.LedgerScope;
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
 * §4.11's AD-05 over HTTP (#305): the double-entry ledger, readable by account and by
 * campaign, with both sides of every entry shown together.
 *
 * <p><strong>Postings, not entries.</strong> §7.2's invariant is stated per transaction —
 * for every {@code transaction_id}, the debits equal the credits — so a response that
 * streamed rows would be one in which the platform's central accounting rule is invisible.
 * Every posting carries all of its sides, including the ones an account filter did not
 * match; see {@code LedgerScope} for why a filter must never decide which half of a double
 * entry is shown.
 *
 * <p><strong>The balances travel with the page.</strong> Twenty-five postings out of a
 * hundred thousand say nothing about whether escrow holds what it should. The totals do, and
 * an endpoint that made them a second request would be one where the only number worth
 * reading arrives last.
 *
 * <p>{@code no-store} and staff-only for the reasons {@link AuditTrailController} gives, and
 * one more: §22.1 treats these rows as a regulatory record, which makes who read them a fact
 * worth keeping as much as who wrote them.
 */
@RestController
@RequestMapping("/v1/admin/ledger")
public class LedgerController {

    private final ConsoleReadService console;
    private final AdminConsoleProperties properties;

    public LedgerController(ConsoleReadService console, AdminConsoleProperties properties) {
        this.console = console;
        this.properties = properties;
    }

    /**
     * One page of postings, newest first, with the standing balances behind them.
     *
     * @param account one of §7.2's six — {@code escrow}, {@code platform_fee},
     *     {@code psp_fee}, {@code tax_payable}, {@code refunds}, {@code creator:{id}}. A
     *     value outside that set is a 400 rather than an empty page, which would read as
     *     "this account has nothing in it"
     * @param projectId one campaign, or absent for the whole platform. Combines with
     *     {@code account}, unlike the payment log's two filters — {@code ledger_entries}
     *     has an index that leads on both
     * @param after the previous page's {@code nextCursor}. A number, because the ledger's
     *     primary key is a sequence rather than a UUID
     * @param limit how many <strong>postings</strong>, clamped to
     *     {@code ideanest.admin.ledger.max-page-size}. Not how many rows: a posting is at
     *     least two entries and occasionally five
     */
    @GetMapping
    public ResponseEntity<LedgerResponses.View> ledger(
            @AuthenticationPrincipal Jwt accessToken,
            @RequestParam(required = false) String account,
            @RequestParam(required = false) UUID projectId,
            @RequestParam(required = false) Long after,
            @RequestParam(required = false) Integer limit) {

        LedgerScope scope = new LedgerScope(accountOf(account), projectId);

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(LedgerResponses.of(console.ledger(
                        staffOf(accessToken), scope, after, properties.ledger().effective(limit))));
    }

    /**
     * The account filter, as a value rather than as whatever was typed.
     *
     * <p>{@link LedgerAccount}'s constructor is the check: it refuses anything V41's
     * constraint would also refuse, and it throws {@link IllegalArgumentException}, which
     * {@link ConsoleExceptionHandler} turns into a 400 naming the parameter. Parsing here
     * rather than binding a {@code LedgerAccount} directly, because a converter would make
     * the same refusal arrive as a binding failure with no room to say which of the six was
     * expected.
     */
    private static LedgerAccount accountOf(String account) {
        return account == null || account.isBlank() ? null : new LedgerAccount(account);
    }

    /** Whoever is signed in. See {@link AuditTrailController} on why the token and not the body. */
    private static UUID staffOf(Jwt accessToken) {
        return UUID.fromString(accessToken.getSubject());
    }
}
