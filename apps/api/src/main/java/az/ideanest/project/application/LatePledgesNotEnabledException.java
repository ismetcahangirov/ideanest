package az.ideanest.project.application;

import java.util.UUID;

/**
 * The campaign has not offered late pledges — §4.5's PL-16.
 *
 * <p>Distinct from {@link ProjectTransitionNotAllowedException}, which is about the
 * edge. This one is about the switch: the campaign is in the state the edge starts
 * from and the creator has simply not said yes, and the correction is one checkbox in
 * the campaign editor rather than anything to do with §6.1.
 *
 * <p>Two facts rather than one for the reason §7.2 keeps two columns: "we offer late
 * pledges" is a decision a creator takes once, and "the window is open until Friday"
 * is a fact with an end. Folding them together would make opening the window the act
 * of enabling the feature, and a creator who wanted to stop early would have to take a
 * transition they cannot undo.
 */
public class LatePledgesNotEnabledException extends RuntimeException {

    public LatePledgesNotEnabledException(UUID projectId) {
        super("Project " + projectId + " has not enabled late pledges");
    }
}
