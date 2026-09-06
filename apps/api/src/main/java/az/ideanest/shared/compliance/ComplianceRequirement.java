package az.ideanest.shared.compliance;

/**
 * What an override may waive — V66's {@code requirement}, issue #436.
 *
 * <p><strong>Closed, and closed in two places</strong>, for {@code DocumentKind}'s reason:
 * this enum and V66's {@code compliance_overrides_requirement_known} say the same four names,
 * because either alone is half a rule. A gate names one of these values in code, so free text
 * in the column would be an override nothing honours, granted by somebody who watched the
 * screen say it worked.
 *
 * <p>Four rather than one general "compliance" exception, because a gate asks about its own
 * rule. An override granted because a creator's passport is at an embassy abroad has nothing
 * to say about whether they signed the creator agreement, and a single value would have let
 * the first waiver open all four doors.
 */
public enum ComplianceRequirement {

    /** #431's gate: a payout waits on an approved identity verification. */
    IDENTITY_VERIFICATION,

    /** #430's: whether this creator must be a registered legal entity rather than a person. */
    LEGAL_SUBJECT,

    /** #432's: money goes to a destination somebody confirmed belongs to the creator. */
    PAYOUT_DESTINATION,

    /**
     * #429's: the creator agreement carries a SİMA İmza signature and not only a tick.
     *
     * <p>The one most likely to be waived and the one it costs most to waive, which is why it
     * is a value here rather than a nullable column on the acceptance: a signature that was
     * excused is a fact somebody has to be able to find, and a null is not a fact.
     */
    AGREEMENT_SIGNATURE
}
