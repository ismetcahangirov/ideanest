package az.ideanest.notification.api;

import az.ideanest.notification.application.EmailTemplateEditor;
import az.ideanest.notification.domain.NotificationType;
import az.ideanest.notification.application.TemplateOverrides;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * §4.11's AD-15, third verb — §12.3, issue #315.
 *
 * <h2>Beside the preview rather than instead of it</h2>
 *
 * <p>{@code EmailTemplateController} owns {@code GET .../{type}/preview} and
 * {@code POST .../test-send}, both built by #86. This adds the editing #86 named as
 * missing, on the same prefix, so a person edits and previews without changing screens —
 * and the preview goes through the same composer the sender uses, which is now
 * override-aware. A preview that ignored overrides would be a picture of the shipped copy
 * rather than of the message.
 *
 * <h2>{@code PUT} with the locale in the path</h2>
 *
 * <p>An override is per template and per locale, and V52 makes that pair its identity — so
 * the pair is the address. {@code PUT} rather than {@code POST} because the caller is
 * stating what the copy should now be, and re-sending the same body is a version that says
 * the same thing rather than a second edit that means something else.
 *
 * <p>Every route needs {@code CONFIGURE_PLATFORM}, checked in the service.
 */
@RestController
@RequestMapping("/v1/admin/email-templates")
public class EmailTemplateEditorController {

    private final EmailTemplateEditor editor;

    public EmailTemplateEditorController(EmailTemplateEditor editor) {
        this.editor = editor;
    }

    /** The shipped copy, the override if there is one, and the placeholders that must stay. */
    @GetMapping("/{type}/copy")
    public ResponseEntity<EmailTemplateEditorResponses.Draft> read(
            @AuthenticationPrincipal Jwt accessToken, @PathVariable NotificationType type) {

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(EmailTemplateEditorResponses.Draft.of(
                        editor.read(callerOf(accessToken), type, TemplateOverrides.RENDER_LOCALE)));
    }

    /** Every version ever written, newest first. */
    @GetMapping("/{type}/versions")
    public ResponseEntity<EmailTemplateEditorResponses.History> history(
            @AuthenticationPrincipal Jwt accessToken, @PathVariable NotificationType type) {

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(EmailTemplateEditorResponses.History.of(
                        editor.history(callerOf(accessToken), type, TemplateOverrides.RENDER_LOCALE)));
    }

    /** Writes a new version and makes it live. */
    @PutMapping("/{type}/copy")
    public ResponseEntity<EmailTemplateEditorResponses.Version> edit(
            @AuthenticationPrincipal Jwt accessToken,
            @PathVariable NotificationType type,
            @Valid @RequestBody EditRequest request) {

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(EmailTemplateEditorResponses.Version.of(editor.edit(
                        callerOf(accessToken),
                        type,
                        TemplateOverrides.RENDER_LOCALE,
                        request.subject(),
                        request.body(),
                        request.note())));
    }

    /**
     * Takes the override out of service.
     *
     * <p>204 whether or not there was one. Withdrawing an override that is not there is not
     * an error — the platform ends up sending the shipped copy either way, which is what
     * the caller asked for.
     */
    @DeleteMapping("/{type}/copy")
    public ResponseEntity<Void> withdraw(
            @AuthenticationPrincipal Jwt accessToken, @PathVariable NotificationType type) {

        editor.withdraw(callerOf(accessToken), type, TemplateOverrides.RENDER_LOCALE);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    /**
     * New copy.
     *
     * @param subject what the recipient sees in their inbox list
     * @param body the first paragraph. Only the first — the service has why the second is
     *     not editable
     * @param note why it was changed, for whoever reads the history in a year
     */
    public record EditRequest(
            @NotBlank @Size(max = 300) String subject,
            @NotBlank @Size(max = 50000) String body,
            @Size(max = 2000) String note) {
    }

    private static UUID callerOf(Jwt accessToken) {
        return UUID.fromString(accessToken.getSubject());
    }
}
