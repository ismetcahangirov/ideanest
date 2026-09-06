package az.ideanest.shared.signature;

/** The four things that become of a signing session — issue #429. */
public enum SigningState {

    /** The citizen approved it. A {@link SignatureOnFile} exists. */
    SIGNED,

    /** Sent, and not yet answered. The caller asks again. */
    PENDING,

    /** The citizen declined. Ordinary, retryable, and nothing is written against the account. */
    CANCELLED,

    /** Nobody answered in time. Ordinary in the same way — a phone was in a pocket. */
    EXPIRED
}
