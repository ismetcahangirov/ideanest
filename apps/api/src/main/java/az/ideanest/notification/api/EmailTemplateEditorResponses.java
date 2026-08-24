package az.ideanest.notification.api;

import az.ideanest.notification.application.EmailTemplateEditor;
import az.ideanest.notification.domain.EmailTemplateVersion;
import az.ideanest.notification.domain.NotificationType;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * AD-15's editor, as the service describes it — issue #315.
 */
public final class EmailTemplateEditorResponses {

    private EmailTemplateEditorResponses() {
    }

    /** One stored version. */
    public record Version(
            UUID id,
            String templateKey,
            String locale,
            int version,
            String subject,
            String body,
            List<String> requiredPlaceholders,
            boolean live,
            String note,
            Instant createdAt,
            UUID createdBy) {

        public static Version of(EmailTemplateVersion version) {
            return new Version(
                    version.id(),
                    version.templateKey(),
                    version.locale(),
                    version.version(),
                    version.subject(),
                    version.body(),
                    version.requiredPlaceholders(),
                    version.live(),
                    version.note(),
                    version.createdAt(),
                    version.createdBy());
        }
    }

    /**
     * What the editor opens with.
     *
     * <p><strong>Both the shipped copy and the override travel.</strong> An editor showing
     * only the current text gives nobody a way to see what they changed it from — and the
     * shipped copy is what withdrawing an override returns to, so it is also the preview of
     * the undo.
     *
     * @param requiredPlaceholders the {@code MessageFormat} argument indices an override
     *     must keep. Sent so the screen can say which are missing before the service
     *     refuses — {@code MissingTemplatePlaceholderException} is the rule and this is how
     *     somebody avoids meeting it
     * @param override null when the platform is sending the shipped copy
     */
    public record Draft(
            NotificationType type,
            String locale,
            String shippedSubject,
            String shippedBody,
            Set<String> requiredPlaceholders,
            Version override) {

        public static Draft of(EmailTemplateEditor.TemplateDraft draft) {
            return new Draft(
                    draft.type(),
                    draft.locale(),
                    draft.shippedSubject(),
                    draft.shippedBody(),
                    draft.requiredPlaceholders(),
                    draft.override() == null ? null : Version.of(draft.override()));
        }
    }

    /** Every version of one template, newest first. */
    public record History(List<Version> versions) {

        public static History of(List<EmailTemplateVersion> versions) {
            return new History(versions.stream().map(Version::of).toList());
        }
    }
}
