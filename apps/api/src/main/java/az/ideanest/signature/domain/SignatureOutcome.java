package az.ideanest.signature.domain;

/**
 * What became of a signing session — issue #428.
 *
 * <p><strong>Every value here is an ordinary outcome.</strong> §9.4 draws the line this enum
 * sits on: "a decline is a value and an unreachable provider is a throw". A citizen who
 * cancels, or who leaves their phone face-down until the session expires, has done something
 * perfectly normal that the platform must show as "not signed yet" and let them retry. A SİMA
 * that cannot be reached is {@link SignatureProviderUnavailableException}, because then the
 * platform does not know what the citizen did.
 *
 * <p>The distinction is not academic. A creator whose signing session expired must be told
 * their draft is untouched and offered the button again; a creator who met an outage must be
 * told the signing service is unavailable. Collapsing the two produces the failure #428 names
 * — "a submission that failed for an unexplained reason".
 */
public enum SignatureOutcome {

    /** The citizen signed. The only value that comes with a {@link StoredSignature}. */
    SIGNED,

    /** Begun and not yet answered. The citizen's phone is showing the prompt. */
    PENDING,

    /** The citizen declined it in their SİMA app. */
    CANCELLED,

    /**
     * Nobody answered in time and the provider closed the session.
     *
     * <p>Separate from {@link #CANCELLED} because the platform says different things about
     * them: a cancellation is a decision to respect, and an expiry is a prompt to offer again.
     */
    EXPIRED
}
