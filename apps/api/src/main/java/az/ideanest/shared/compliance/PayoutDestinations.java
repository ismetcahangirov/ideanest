package az.ideanest.shared.compliance;

import java.util.Optional;
import java.util.UUID;

/**
 * The two questions the payout asks about where a creator's money goes — part of issue
 * #432, asked across a module boundary.
 *
 * <p>{@link CreatorStandings}' shape, and the same argument. The payout module refuses to
 * release money on the strength of a row in {@code payout_destinations}, and may no more
 * read that table than it may read {@code identity_verifications}. So it names this, and
 * {@code compliance.application.CreatorPayoutDestinations} answers it.
 *
 * <p>The destination lives in the compliance module rather than the payout module because
 * V66 already put it there: COMPLIANCE "is the role that decides identity, legal subject
 * and payout destination — the three questions this epic added that are about who somebody
 * is rather than about what they posted". A destination the payout module owned would be a
 * destination the role that sends money also administered, which is what V66 refused.
 *
 * <h2>Two methods, and why the standing is not enough on its own</h2>
 *
 * <p>{@link #standingOf} is the gate's question and folds #436's override into the answer.
 * {@link #referenceFor} is the send's, and it is deliberately narrower: a waived
 * destination releases the payout but still has to have a token behind it, because an
 * override excuses a check and cannot conjure an account number. A caller that read the
 * standing and assumed a reference would send a payout to null.
 */
public interface PayoutDestinations {

    /** Where this creator stands, with any live override already applied. */
    DestinationStanding standingOf(UUID creatorId);

    /**
     * The provider token to send to, if there is one that may be sent to.
     *
     * <p>Empty when the creator has recorded nothing, when the standing does not release a
     * payout, and — the case a caller is most likely to forget — when the destination was
     * tokenised by a provider that is not the one this deployment is configured to send
     * through. A token is meaningless to a provider that did not issue it, and passing one
     * along is a refusal at best.
     *
     * @param provider the {@code ProviderName} the payout will be sent through, as its
     *     enum constant name. A string rather than the enum because that type is the
     *     payment module's domain and this contract is shared; {@code
     *     PayoutDestinationSchemaTests} keeps the vocabulary from drifting
     */
    Optional<String> referenceFor(UUID creatorId, String provider);
}
