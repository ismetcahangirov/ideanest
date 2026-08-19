package az.ideanest.notification.infrastructure;

import java.util.List;
import java.util.Objects;

/**
 * What one email says, decided before anything decides how it looks.
 *
 * <p><strong>This is the "typed" in #86's typed templates.</strong> The alternative
 * considered first was a Thymeleaf file per notification type — twenty of them in HTML
 * and twenty more in plain text — and it was rejected: forty files that each restate the
 * same header, the same button and the same footer means forty places a change to any of
 * them has to be made, and a type whose two versions drift apart sends a plain-text
 * reader something different from what the HTML reader got.
 *
 * <p>So the shape of an email is one HTML layout and one text layout, and what differs
 * per type is this record. {@code EmailComposer} builds it in an exhaustive switch over
 * {@code NotificationType}, which means a new type is a compilation error until somebody
 * decides what it says, rather than a template that silently resolves to nothing.
 *
 * @param subject the subject line
 * @param headline the first thing in the body, and usually the subject again in a form
 *     that reads as a sentence
 * @param paragraphs the body, one string per paragraph. Never empty
 * @param actionLabel what the button says, or null when the message has nothing to do
 * @param actionUrl where it goes, absolute. Null exactly when {@link #actionLabel} is
 * @param items a digest's members, each a line and a link. Empty for a single message
 */
public record EmailContent(
        String subject,
        String headline,
        List<String> paragraphs,
        String actionLabel,
        String actionUrl,
        List<Item> items) {

    public EmailContent {
        Objects.requireNonNull(subject, "An email has a subject");
        Objects.requireNonNull(headline, "An email opens with something");
        Objects.requireNonNull(paragraphs, "An email has a body");
        if (paragraphs.isEmpty()) {
            throw new IllegalArgumentException("An email with no body is not a message");
        }
        // Whole or absent. A button with no destination renders as dead text, and a
        // destination with no label renders as nothing at all — both are the kind of
        // fault that is invisible until somebody receives it.
        if ((actionLabel == null) != (actionUrl == null)) {
            throw new IllegalArgumentException("A call to action is a label and a destination, or it is neither");
        }
        paragraphs = List.copyOf(paragraphs);
        items = items == null ? List.of() : List.copyOf(items);
    }

    /** A message with a button. */
    public static EmailContent of(
            String subject, String headline, List<String> paragraphs, String actionLabel, String actionUrl) {

        return new EmailContent(subject, headline, paragraphs, actionLabel, actionUrl, List.of());
    }

    /** Whether there is a button to render. */
    public boolean hasAction() {
        return actionLabel != null;
    }

    /** Whether this is a digest — which is the only thing that has members. */
    public boolean hasItems() {
        return !items.isEmpty();
    }

    /**
     * One line of a digest.
     *
     * @param text what happened, in a sentence
     * @param url where to go about it, absolute
     */
    public record Item(String text, String url) {

        public Item {
            Objects.requireNonNull(text, "A digest line says something");
            Objects.requireNonNull(url, "A digest line goes somewhere");
        }
    }
}
