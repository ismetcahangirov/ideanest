package az.ideanest.notification.infrastructure;

import java.util.Objects;

/**
 * One email, rendered, and ready to hand to a relay.
 *
 * <p><strong>Both bodies, always.</strong> A {@code multipart/alternative} message
 * carries a plain-text part and an HTML part, and the client picks. Sending only HTML
 * costs the readers whose client is set to plain text, costs anybody reading with a
 * screen reader that prefers it, and — the part that is measurable — is one of the
 * strongest spam signals there is. Sending only text throws away the call to action.
 *
 * <p>The two are rendered from the same {@link EmailContent}, which is what stops them
 * saying different things.
 *
 * @param subject the subject line, already resolved from the copy
 * @param text the {@code text/plain} part
 * @param html the {@code text/html} part
 */
public record RenderedEmail(String subject, String text, String html) {

    public RenderedEmail {
        Objects.requireNonNull(subject, "An email has a subject");
        Objects.requireNonNull(text, "An email carries a plain-text part");
        Objects.requireNonNull(html, "An email carries an HTML part");
        if (subject.isBlank() || text.isBlank() || html.isBlank()) {
            // A blank part is what a template that failed to resolve produces, and it
            // would otherwise go out as an empty message rather than as an error.
            throw new IllegalArgumentException("An email with an empty part did not render");
        }
    }
}
