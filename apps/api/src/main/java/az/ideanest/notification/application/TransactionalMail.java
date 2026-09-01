package az.ideanest.notification.application;

import java.util.List;
import java.util.Objects;

/**
 * What one email says, in the vocabulary a <em>different</em> module may speak.
 *
 * <p>{@code EmailContent} says the same thing and is the one the renderer takes, but it
 * lives in {@code notification.infrastructure} and {@code ModuleBoundaryTests} is
 * explicit that no other module may name anything in there. So the auth module — which
 * has six messages of its own and no notification row behind any of them — would have
 * had to choose between reaching into internals and building a second transport. This
 * record is the third answer: the same five fields, published where a caller is allowed
 * to see them, mapped to {@code EmailContent} by the one adapter that implements
 * {@link TransactionalMailer}.
 *
 * <h2>A path, not a URL</h2>
 *
 * <p>{@link #actionPath} is {@code "/verify-email?token=…"} rather than an absolute
 * address, and resolving it against this deployment's origin is the adapter's job. The
 * argument is {@code EmailComposer.actionUrl}'s: a preview environment's mail must not
 * send its readers to production, and a caller that concatenated the origin itself would
 * be a second place that decision is made — and the second place is the one that gets it
 * wrong.
 *
 * @param subject the subject line
 * @param headline the first thing in the body, usually the subject as a sentence
 * @param paragraphs the body, one string per paragraph. Never empty
 * @param actionLabel what the button says, or null when the message has nothing to do
 * @param actionPath where it goes, rooted at {@code /}. Null exactly when
 *     {@link #actionLabel} is
 */
public record TransactionalMail(
        String subject, String headline, List<String> paragraphs, String actionLabel, String actionPath) {

    public TransactionalMail {
        Objects.requireNonNull(subject, "An email has a subject");
        Objects.requireNonNull(headline, "An email opens with something");
        Objects.requireNonNull(paragraphs, "An email has a body");
        if (paragraphs.isEmpty()) {
            throw new IllegalArgumentException("An email with no body is not a message");
        }
        // The same whole-or-absent rule EmailContent enforces, checked here as well
        // rather than left to the mapping: a caller that got it wrong should find out at
        // the call site, not three frames into another module.
        if ((actionLabel == null) != (actionPath == null)) {
            throw new IllegalArgumentException("A call to action is a label and a destination, or it is neither");
        }
        if (actionPath != null && !actionPath.startsWith("/")) {
            throw new IllegalArgumentException("A call to action is a path rooted at '/', not " + actionPath);
        }
        paragraphs = List.copyOf(paragraphs);
    }

    /** A message with a button. */
    public static TransactionalMail withAction(
            String subject, String headline, List<String> paragraphs, String actionLabel, String actionPath) {

        return new TransactionalMail(subject, headline, paragraphs, actionLabel, actionPath);
    }

    /**
     * A message with nothing to click.
     *
     * <p>Two of the auth messages are this shape on purpose — a password-change notice
     * and the notice to an address an account is leaving — and a button they do not need
     * would be a button somebody clicks.
     */
    public static TransactionalMail withoutAction(String subject, String headline, List<String> paragraphs) {
        return new TransactionalMail(subject, headline, paragraphs, null, null);
    }
}
