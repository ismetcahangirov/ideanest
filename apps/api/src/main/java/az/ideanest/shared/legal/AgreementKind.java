package az.ideanest.shared.legal;

/**
 * The two of §22.2's eight documents that something refuses without — issue #425.
 *
 * <p><strong>Two rather than eight, deliberately.</strong> The platform is required to have
 * all eight and publishes all eight (#439), but only two are <em>gated</em>: a campaign
 * cannot be submitted without the creator agreement (#426), and a pledge cannot be
 * confirmed without the backer agreement (#427). The other six are read, not agreed to at a
 * moment, and a caller that could name {@code COOKIE_POLICY} here would be a caller that
 * could refuse somebody for not having accepted it.
 *
 * <p><strong>Why it is here and not the legal module's own enum.</strong> The project and
 * pledge modules are the ones that refuse, and {@code ModuleBoundaryTests} forbids them
 * naming {@code legal.domain}. So the vocabulary the gates use is published in
 * {@code shared} — {@code ProjectCapability}'s argument, applied a fourth time — and
 * {@code legal.domain.DocumentKind} maps onto it.
 */
public enum AgreementKind {

    /**
     * What a creator takes on: the obligations of §5.5, the payout terms, the fee, and the
     * indemnity. Required at campaign submission, which is the first moment a campaign
     * costs the platform anything and the first moment its creator owes anybody anything.
     */
    CREATOR_AGREEMENT,

    /**
     * What a backer is told: that a pledge is not a purchase and that a reward is not
     * guaranteed. Required at pledge confirmation, which is §22.3's own moment and §9.2's
     * commitment.
     */
    BACKER_AGREEMENT
}
