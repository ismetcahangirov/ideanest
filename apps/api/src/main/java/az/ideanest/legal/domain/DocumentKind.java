package az.ideanest.legal.domain;

import az.ideanest.shared.legal.AgreementKind;
import java.util.Optional;

/**
 * §22.2's eight documents — issue #425.
 *
 * <p><strong>Closed, and closed in two places.</strong> This enum and V65's
 * {@code legal_documents_kind_known} say the same eight names, because either alone is
 * half a rule: an enum without the constraint is a rule the application knows and a
 * hand-written INSERT does not, and a constraint without the enum is a rule nothing can
 * name. §22.2 lists eight documents the platform is required to have, and a ninth
 * appearing is a change to the specification rather than to a text field.
 *
 * <p><strong>Two of them are agreements and six are policies</strong>, and the difference
 * is whether anything refuses without an acceptance. {@link #agreement()} is where that
 * distinction is drawn, once, so that a gate cannot be written against the cookie policy
 * by a caller who did not know better.
 */
public enum DocumentKind {

    /** The contract between the platform and everybody who uses it. */
    TERMS_OF_USE(null),

    /** §17.4's notice: what is held, why, for how long, and how to be forgotten. */
    PRIVACY_POLICY(null),

    /** What is set, by whom, and what refusing costs. */
    COOKIE_POLICY(null),

    /** What may be campaigned for, and what is removed. §4.9's grounds, stated publicly. */
    PLATFORM_RULES(null),

    /**
     * §5.5's obligations, the payout terms, the fee, and the indemnity. Gated at campaign
     * submission by #426.
     */
    CREATOR_AGREEMENT(AgreementKind.CREATOR_AGREEMENT),

    /**
     * That a pledge is not a purchase and a reward is not guaranteed. Gated at pledge
     * confirmation by #427, which is the moment §22.3 names.
     */
    BACKER_AGREEMENT(AgreementKind.BACKER_AGREEMENT),

    /** §9.7: when money comes back, when it does not, and who decides. */
    DELIVERY_AND_REFUND_POLICY(null),

    /** How a disagreement is raised, mediated and ended. §22.1's forum row. */
    DISPUTE_RESOLUTION_POLICY(null);

    private final AgreementKind agreement;

    DocumentKind(AgreementKind agreement) {
        this.agreement = agreement;
    }

    /**
     * The gate vocabulary this document is named by, if anything gates on it.
     *
     * <p>Empty for the six policies. A policy is published and read; it is not accepted at a
     * moment, and there is no action the platform refuses for want of an acknowledgement of
     * the cookie policy.
     */
    public Optional<AgreementKind> agreement() {
        return Optional.ofNullable(agreement);
    }

    /** The document behind a gate's vocabulary. Total, because every agreement has one. */
    public static DocumentKind of(AgreementKind agreement) {
        return switch (agreement) {
            case CREATOR_AGREEMENT -> CREATOR_AGREEMENT;
            case BACKER_AGREEMENT -> BACKER_AGREEMENT;
        };
    }
}
