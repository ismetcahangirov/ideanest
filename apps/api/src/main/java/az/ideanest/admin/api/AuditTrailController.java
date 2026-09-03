package az.ideanest.admin.api;

import az.ideanest.admin.AdminConsoleProperties;
import az.ideanest.admin.application.ConsoleReadService;
import az.ideanest.audit.AuditCursor;
import az.ideanest.audit.AuditTrailFilter;
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
 * §4.11's AD-14 over HTTP (#314): the record of every privileged action, read back.
 *
 * <p>{@code audit_logs} has been written to since #107 and nothing has ever read it except
 * a psql session. The rows were indexed for exactly the questions this endpoint asks — V21
 * says so — so the screen is the missing half of a feature rather than a new one.
 *
 * <p><strong>{@code /v1/admin/audit} rather than {@code /v1/admin/audit-logs}</strong>,
 * which is the path {@code AuditEntryRepository} guessed at. §10.2 names neither; the
 * shorter one reads as the thing rather than as the table, and every other route under this
 * prefix names a subject rather than a schema object.
 *
 * <p><strong>{@code no-store}, like every other response on this prefix.</strong> These rows
 * carry source addresses and the prose the platform wrote about people's accounts; a shared
 * cache or a browser's disk cache holding one is a disclosure that survives signing out.
 *
 * <p><strong>Staff only, and the check is in the service.</strong> Not on the method: an
 * annotation on a controller is one somebody forgets on the fifth endpoint, and the service
 * is also where the read is recorded — an authorised action nobody recorded and a recorded
 * action nobody authorised are the same defect from opposite ends.
 */
@RestController
@RequestMapping("/v1/admin/audit")
public class AuditTrailController {

    private final ConsoleReadService console;
    private final AdminConsoleProperties properties;

    public AuditTrailController(ConsoleReadService console, AdminConsoleProperties properties) {
        this.console = console;
        this.properties = properties;
    }

    /**
     * One page of the trail, newest first.
     *
     * <p>Newest first where the report queue is oldest first, and the difference is not a
     * preference: a queue is worked from the front and a trail is read from the end.
     *
     * @param entityType narrows to one kind of thing — {@code project}, {@code account},
     *     {@code report}. On its own it answers "everything that has happened to campaigns"
     * @param entityId narrows to one thing, and only together with {@code entityType}: V21's
     *     index leads on the kind, and an identifier alone would not use it. Sent alone it
     *     is dropped, and the response says so rather than pretending it was applied
     * @param actorId narrows to one account's actions. Not combined with the entity filter —
     *     no index serves both, and choosing one silently would be answering a different
     *     question from the one asked. {@code AuditTrailFilter} has the whole argument,
     *     including what is deliberately not filterable and why
     * @param after the {@code nextCursor} of the previous page, or absent for the first.
     *     An opaque string rather than an identifier since #404: the trail is now ordered by
     *     {@code occurred_at}, which is the column this screen displays and is not unique,
     *     so the cursor carries the instant and the identifier that breaks its tie.
     *     {@code AuditCursor} records what was wrong with ordering by the key — the two
     *     columns are written by two different clocks, and the page headed "newest first"
     *     opened on last month. A value this endpoint did not produce is a 400 rather than
     *     the first page, so that a client paging wrongly does not look like one that has
     *     finished
     * @param limit clamped to {@code ideanest.admin.audit.max-page-size} rather than
     *     refused, following the report queue: a client asking for a thousand is asking for
     *     as much as it can have, and a 400 there only teaches it to ask for the maximum
     */
    @GetMapping
    public ResponseEntity<AuditTrailResponses.Page> trail(
            @AuthenticationPrincipal Jwt accessToken,
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) UUID entityId,
            @RequestParam(required = false) UUID actorId,
            @RequestParam(required = false) String after,
            @RequestParam(required = false) Integer limit) {

        AuditTrailFilter filter = new AuditTrailFilter(entityType, entityId, actorId);

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(AuditTrailResponses.of(console.auditTrail(
                        staffOf(accessToken),
                        filter,
                        AuditCursor.decode(after),
                        properties.audit().effective(limit))));
    }

    /**
     * Whoever is signed in.
     *
     * <p>Read from the token's subject and never from the request, for the reason every
     * other administration endpoint gives: an actor who could name themselves would be
     * writing the record as well as taking the decision.
     */
    private static UUID staffOf(Jwt accessToken) {
        return UUID.fromString(accessToken.getSubject());
    }
}
