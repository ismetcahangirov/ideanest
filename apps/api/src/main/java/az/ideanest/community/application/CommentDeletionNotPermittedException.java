package az.ideanest.community.application;

import java.util.UUID;

/**
 * Somebody tried to remove a comment that is not theirs to remove.
 *
 * <p><strong>403 and not 404</strong>, unlike {@link CommentNotFoundException}. The
 * caller can already see this comment — it is on a public page they were reading when
 * they pressed the button — so there is nothing left to hide, and answering "no such
 * comment" for one that is plainly on screen is a refusal a client cannot report
 * honestly. {@code CapabilityNotGrantedException} draws the same line for the same
 * reason.
 */
public class CommentDeletionNotPermittedException extends RuntimeException {

    public CommentDeletionNotPermittedException(UUID commentId) {
        super("Comment " + commentId + " may only be removed by its author or the campaign's team");
    }
}
