package az.ideanest.community.application;

import java.util.UUID;

/**
 * There is no update there that this caller may know about — issue #297.
 *
 * <p>404, and deliberately the same answer for three different situations: an identifier
 * that names nothing, an update whose {@code publishedAt} is still in the future, and one
 * under a campaign a stranger cannot see. Distinguishing them would turn the report
 * endpoint into an oracle — {@code PublicProjectUpdates} has the argument, and
 * {@code CommentNotFoundException} makes the same one about tombstones.
 */
public class ProjectUpdateNotFoundException extends RuntimeException {

    public ProjectUpdateNotFoundException(UUID updateId) {
        super("No update " + updateId);
    }
}
