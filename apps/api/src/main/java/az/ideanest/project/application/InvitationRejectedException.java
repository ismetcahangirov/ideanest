package az.ideanest.project.application;

/**
 * An invitation that cannot be accepted: unknown, expired, revoked, already used,
 * or addressed to somebody else.
 *
 * <p><strong>One exception and one message per outcome, and deliberately no
 * distinction a caller could enumerate with.</strong> The same reasoning as
 * {@code VerificationRejectedException}: the token is a bearer credential, so the
 * only thing an unauthenticated guess should learn is that the guess failed. The
 * messages here do differ — "this link has expired" and "this link has already been
 * used" are both useful, and both are about a link the caller demonstrably holds —
 * but an unknown token is never told apart from one belonging to another campaign.
 *
 * <p>Answered as a 409 rather than a 404: the caller is authenticated, the request
 * is well formed, and what refuses it is the state the invitation is in. A creator
 * who withdrew a grant an hour ago and an invitee clicking a week-old link are the
 * two ordinary cases, and both need to be told which one happened.
 */
public class InvitationRejectedException extends RuntimeException {

    public InvitationRejectedException(String message) {
        super(message);
    }
}
