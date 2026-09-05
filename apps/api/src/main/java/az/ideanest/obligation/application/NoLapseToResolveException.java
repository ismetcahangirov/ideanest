package az.ideanest.obligation.application;

import java.util.UUID;

/**
 * There is no open escalation on that campaign — #437.
 *
 * <p>One exception for three situations that are the same to the moderator looking at the screen:
 * no obligation, no lapse, and a lapse somebody else has just closed. The recovery is identical
 * in all three — reload the queue — and three codes in front of one button would be three
 * messages that all mean "it is not there any more".
 */
public class NoLapseToResolveException extends RuntimeException {

    private final transient UUID projectId;

    public NoLapseToResolveException(UUID projectId) {
        super("Campaign %s has no open update-obligation escalation.".formatted(projectId));
        this.projectId = projectId;
    }

    public UUID projectId() {
        return projectId;
    }
}
