package az.ideanest.notification.api;

import az.ideanest.notification.application.EmailTemplates;
import az.ideanest.notification.domain.NotificationType;
import az.ideanest.notification.infrastructure.RenderedEmail;
import jakarta.mail.MessagingException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * AD-15's two built halves: look at an email template, and send yourself one.
 *
 * <p>Under {@code /v1/admin/email-templates}, beside the moderation routes
 * {@code ReportQueueController} serves. §10.2 reserves the {@code /v1/admin} prefix for
 * administration and does not enumerate these; AD-15 names the capability.
 *
 * <p><strong>{@code SecurityConfiguration} is not touched.</strong> Its
 * {@code /v1/admin/**} matcher requires an active account and nothing more — there is no
 * role model until epic #100 — so every method here refuses one layer in, through
 * {@code EmailTemplates} and {@code PlatformStaff}, exactly as the moderation queue does.
 *
 * <p>Editing a template is the third of AD-15 and is not here. {@code EmailTemplates}
 * says why.
 */
@RestController
@RequestMapping("/v1/admin/email-templates")
public class EmailTemplateController {

    private final EmailTemplates templates;

    public EmailTemplateController(EmailTemplates templates) {
        this.templates = templates;
    }

    /** Every template there is: the types §4.10 gives an email column. */
    @GetMapping
    public EmailTemplateListResponse templates(@AuthenticationPrincipal Jwt accessToken) {
        return EmailTemplateListResponse.of(templates.previewable(callerOf(accessToken)));
    }

    /**
     * One template, rendered against a sample document.
     *
     * <p><strong>Answered as the body it would be sent as, not as JSON describing
     * one.</strong> {@code text/html} for the HTML part and {@code text/plain} for the
     * other, so that pointing a browser at this URL shows the email — which is what a
     * preview is for. A JSON envelope with the markup inside a string would have to be
     * unwrapped by something before anybody could look at it, and the something would be
     * a second renderer.
     *
     * <p>The subject travels in {@code X-Email-Subject} for the same reason: it is part
     * of the message and there is nowhere else to put it in a response whose body is the
     * message.
     *
     * @param format {@code html} or {@code text}. Defaults to HTML, which is what a
     *     reader with a browser wants; the text part is the one that has to be asked for,
     *     and it is the one worth checking because nothing else renders it
     */
    @GetMapping(
            path = "/{type}/preview",
            // Declared, so that the published contract says what this answers with. Left
            // off, springdoc infers application/json from the return type and the
            // document would describe a JSON string where the body is a mail part.
            produces = {MediaType.TEXT_HTML_VALUE, MediaType.TEXT_PLAIN_VALUE})
    public ResponseEntity<String> preview(
            @AuthenticationPrincipal Jwt accessToken,
            @PathVariable NotificationType type,
            @RequestParam(defaultValue = "html") String format) {

        RenderedEmail email = templates.preview(callerOf(accessToken), type);
        boolean asText = "text".equalsIgnoreCase(format);

        return ResponseEntity.ok()
                .contentType(asText ? MediaType.TEXT_PLAIN : MediaType.TEXT_HTML)
                // Not a header a client sends back and not part of the contract's
                // schemas, so it is prefixed rather than pretending to be standard.
                .header("X-Email-Subject", email.subject())
                // A preview is generated per request and is not somebody's mail, but it
                // is still the body of a message about a sample pledge -- and a cache
                // holding staff-only content on a shared proxy is a category of accident
                // worth simply not having.
                .header("Cache-Control", "no-store")
                .body(asText ? email.text() : email.html());
    }

    /**
     * Sends this template to the calling staff member's own address.
     *
     * <p><strong>There is no recipient parameter, and that is the design.</strong>
     * {@code EmailTemplates} makes the argument: an authenticated endpoint taking an
     * arbitrary address and a platform-branded template is a way to send real-looking
     * payment mail to anybody, and one compromised staff account is the whole cost of
     * entry.
     *
     * <p>{@code 204}, because there is nothing to say beyond that it went. What happened
     * afterwards is between the relay and the mailbox, and this endpoint cannot see it —
     * for the same reason {@code email_deliveries} has no {@code delivered_at}.
     */
    @PostMapping("/{type}/test-send")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void testSend(@AuthenticationPrincipal Jwt accessToken, @PathVariable NotificationType type)
            throws MessagingException {

        templates.testSend(callerOf(accessToken), type);
    }

    /** The authenticated caller, from the token's subject and never from the request. */
    private static UUID callerOf(Jwt accessToken) {
        return UUID.fromString(accessToken.getSubject());
    }
}
