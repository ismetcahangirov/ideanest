package az.ideanest.project.application;

import az.ideanest.shared.legal.AgreementInForce;
import java.util.UUID;

/**
 * A campaign could not be submitted because its creator has not accepted the creator
 * agreement — issue #426.
 *
 * <p><strong>The second gate on publishing, and it is not the same kind of gate as the
 * first.</strong> {@link SubscriptionRequiredException} means "you have not paid", and money
 * fixes it. This means "you have not agreed to the terms you would be operating under", and
 * nothing but reading them fixes it. Collapsing the two into one refusal would send somebody
 * to a price list to solve a problem money cannot solve.
 *
 * <p><strong>It names the version.</strong> Not just the document: a creator who accepted
 * version 3 and is meeting this because version 4 was published this morning needs to be
 * sent to version 4, and a refusal saying only "accept the creator agreement" would send
 * them to a page whose Accept button they have already pressed. The client renders the
 * version and the effective date from these fields.
 *
 * <p><strong>Raised against the creator, never the caller.</strong> {@code CreatorAgreementGate}
 * argues why, and the argument is stronger than the subscription's: a collaborator's
 * acceptance would let a creator take on no obligations at all by asking somebody else to
 * press the button, and would produce a campaign whose obligations are owed by a person with
 * no control over it.
 */
public class AgreementRequiredException extends RuntimeException {

    /**
     * What is missing — issue #429.
     *
     * <p>The refusal keeps one {@code code}, {@code AGREEMENT_REQUIRED}, because it is one
     * refusal: the creator has not agreed to what they must agree to. What differs is the next
     * action, and a creator sent to tick a box they have already ticked would conclude the
     * platform is broken. #429 asks for exactly this — "a reason that says 'signature', not
     * 'acceptance'".
     */
    public enum Requirement {

        /** Nothing has been accepted. The creator reads the document and ticks. */
        ACCEPTANCE,

        /**
         * It has been accepted, and this campaign needs it signed.
         *
         * <p>Reached only when {@code ideanest.legal.signature.enabled} is on and the rule
         * catches this campaign — #424's threshold, which is configuration and not a constant.
         */
        SIGNATURE
    }

    private final UUID projectId;
    private final transient AgreementInForce agreement;
    private final transient Requirement requirement;

    public AgreementRequiredException(UUID projectId, AgreementInForce agreement) {
        this(projectId, agreement, Requirement.ACCEPTANCE);
    }

    public AgreementRequiredException(UUID projectId, AgreementInForce agreement, Requirement requirement) {
        super("Campaign %s cannot be submitted until its creator %s %s version %d"
                .formatted(
                        projectId,
                        requirement == Requirement.SIGNATURE ? "signs" : "accepts",
                        agreement.kind(),
                        agreement.version()));
        this.projectId = projectId;
        this.agreement = agreement;
        this.requirement = requirement;
    }

    public Requirement requirement() {
        return requirement;
    }

    public UUID projectId() {
        return projectId;
    }

    public AgreementInForce agreement() {
        return agreement;
    }
}
