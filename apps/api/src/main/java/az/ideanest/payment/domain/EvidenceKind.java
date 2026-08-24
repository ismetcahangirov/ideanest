package az.ideanest.payment.domain;

/**
 * What a piece of dispute evidence is — V54, issues #68 and #308.
 *
 * <p>A closed set because the networks ask for categories rather than for documents: a
 * representment is assembled by answering whether delivery can be shown, and whether the
 * cardholder can be shown to have agreed to the terms. A screen with one free-text box
 * produces a case nobody can check for gaps.
 *
 * <p>{@link #OTHER} exists and is deliberately last. A taxonomy with no escape hatch gets
 * one anyway, spelled as whichever category is nearest — and then the counts on the
 * categories that matter are wrong.
 */
public enum EvidenceKind {

    /** What the backer was charged, and for what. */
    RECEIPT,

    /** That the reward was sent, and where. */
    SHIPPING_PROOF,

    /** What was said to the backer, and when. */
    COMMUNICATION,

    /** That they accepted the platform's terms at the moment of pledging. */
    TERMS_ACCEPTANCE,

    /** What the platform's refund policy said at the time. */
    REFUND_POLICY,

    /** Sign-ins, page views, anything showing the cardholder was the one who pledged. */
    ACTIVITY_LOG,

    OTHER
}
