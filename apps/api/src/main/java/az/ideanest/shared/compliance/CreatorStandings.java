package az.ideanest.shared.compliance;

import java.util.UUID;

/**
 * The question the payout gate asks about a creator — issue #431, across a module boundary.
 *
 * <p>The payout module refuses to release money on the strength of a row in
 * {@code identity_verifications}, and may no more read that table than the project module may
 * read {@code document_acceptances}. So it names this, and
 * {@code verification.application.IdentityStandings} answers it.
 *
 * <p><strong>The answer folds three sources into one value</strong> — the verification row,
 * {@code ideanest.verification.required}, and #436's override — because a caller that had to
 * consult all three would be a second copy of the rule, and the second copy is the one that
 * gets a case wrong. {@link VerificationStanding#releasesPayout()} is the whole of the gate's
 * logic.
 */
public interface CreatorStandings {

    /** Where this creator stands, with the flag and any live override already applied. */
    VerificationStanding of(UUID creatorId);

    /**
     * Ask this creator for identity documents, if there is anything to ask for.
     *
     * <p>#431: "The creator is notified and asked for documents, through the existing request
     * flow." Called by the gate at the moment the payout is held, because that is the moment
     * the platform knows it needs something — a request raised earlier, on submission, would
     * send every creator through document review to find out whether their idea funds.
     *
     * <p>Does nothing when the standing does not ask the creator for anything, so a caller may
     * call it unconditionally and a payout looked at twice does not raise two requests.
     */
    void requestIfNeeded(UUID creatorId);
}
