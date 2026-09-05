package az.ideanest.compliance.domain;

/**
 * Why a requirement was waived — V66's {@code reason}, issue #436.
 *
 * <p><strong>A closed set plus free text, and both halves are required.</strong> V58 makes the
 * same argument about a rejection reason and this is it in the other direction: the closed
 * value is what an auditor counts and filters on, and the note is what they read. A free-text
 * field alone is where somebody eventually writes "asked on the phone"; a closed set alone is
 * five words that never explain the case they were reached for.
 *
 * <p>Deliberately short. Every value here is a case somebody can describe in advance, and an
 * override that fits none of them is one that should be escalated rather than filed under an
 * {@code OTHER} nobody can act on — which is why there is no {@code OTHER}.
 */
public enum OverrideReason {

    /**
     * The document exists and cannot be produced from where the person is.
     *
     * <p>The ordinary case, and the reason this mechanism exists at all: a creator abroad
     * whose passport is with an embassy is not a compliance risk, and a platform with no
     * override would answer them by refusing a campaign for six weeks.
     */
    DOCUMENT_UNAVAILABLE_ABROAD,

    /**
     * SİMA, the identity provider, or the payment provider could not be reached, and waiting
     * costs more than proceeding.
     *
     * <p>The value that most needs an expiry, and V66 gives every override one. An outage
     * ends; an override granted during one and never revoked is a control that was switched
     * off on a Tuesday afternoon and left off.
     */
    PROVIDER_OUTAGE,

    /** Somebody established the same fact another way, and the note says how. */
    VERIFIED_BY_OTHER_MEANS,

    /** Counsel said the requirement does not apply to this case. #423's adviser. */
    LEGAL_ADVICE,

    /**
     * The platform put the person in this position.
     *
     * <p>Separate from the rest because it is the one that should generate work elsewhere: an
     * override granted for this reason is a defect somebody has to fix, and counting them is
     * how anybody finds out.
     */
    PLATFORM_ERROR
}
