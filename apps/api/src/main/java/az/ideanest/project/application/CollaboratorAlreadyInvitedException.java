package az.ideanest.project.application;

/**
 * That address already has a live invitation or an active grant on this campaign.
 *
 * <p>Refused rather than merged into the existing row. A second invitation would
 * issue a second token, and the creator would then have two links in circulation
 * for one person while believing they had changed their mind about the first: the
 * way to alter a grant is {@code PATCH /v1/collaborators/{id}}, and the way to end
 * one is to revoke it.
 *
 * <p>Checked in the service so that the answer is a 409 the creator can act on,
 * and enforced again by {@code collaborators_live_invitation_key} — which is the
 * check that cannot lose a race between two managers inviting the same person at
 * once.
 */
public class CollaboratorAlreadyInvitedException extends RuntimeException {

    public CollaboratorAlreadyInvitedException(String message) {
        super(message);
    }
}
