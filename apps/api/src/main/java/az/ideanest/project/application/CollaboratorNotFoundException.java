package az.ideanest.project.application;

import java.util.UUID;

/**
 * No such collaborator, or none on a campaign this caller may manage.
 *
 * <p>One exception for both, for the reason {@link ProjectNotFoundException} gives:
 * a grant identifier that answered 403 when it existed and 404 when it did not
 * would say whether an address somebody guessed is on a campaign they cannot see.
 * The two cases are told apart by {@link ProjectAccess}, which refuses the
 * campaign first — so by the time a caller reaches a real row they are entitled to
 * know it is there.
 */
public class CollaboratorNotFoundException extends RuntimeException {

    public CollaboratorNotFoundException(UUID collaboratorId) {
        super("No collaborator " + collaboratorId + " is visible to this caller");
    }
}
