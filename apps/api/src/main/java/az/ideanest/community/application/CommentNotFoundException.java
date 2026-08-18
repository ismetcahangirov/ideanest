package az.ideanest.community.application;

import java.util.UUID;

/**
 * No such comment, or none this caller is allowed to act on.
 *
 * <p><strong>Four cases, deliberately one exception.</strong> The comment never
 * existed; it was removed; it belongs to a campaign the public cannot see; or the
 * campaign does not exist at all. Telling them apart would make the reply and report
 * routes an oracle for what is under a draft campaign, which is the thing
 * {@code ProjectNotFoundException} spends its class comment refusing to be — and here
 * it matters twice over, because a removed comment is frequently one trust and safety
 * has just acted on.
 */
public class CommentNotFoundException extends RuntimeException {

    public CommentNotFoundException(UUID commentId) {
        // The identifier and nothing else. This reaches a log line, and what somebody
        // wrote is the part §17.4 keeps out of one.
        super("No comment " + commentId + " is visible to this caller");
    }
}
