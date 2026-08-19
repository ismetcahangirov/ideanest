package az.ideanest.notification.domain;

/**
 * What somebody asked for on one category and one channel.
 *
 * <p>Three alternatives in one column rather than a boolean beside a digest flag,
 * because they are alternatives: a row that is both off and digesting is a state
 * somebody would eventually have to interpret, and they would interpret it wrongly at
 * least once.
 */
public enum DeliveryMode {

    /**
     * Do not send this at all.
     *
     * <p>Nothing is written — not even a suppressed row. A record of everything the
     * platform decided not to tell somebody is a log of their reading habits with a
     * retention policy nobody has argued for, and it would be the largest table in the
     * schema. The absence is the honouring.
     */
    OFF,

    /** Send it when it happens. The default for everything — see {@link DeliveryPolicy}. */
    IMMEDIATE,

    /**
     * Hold it, and combine it with the others.
     *
     * <p>§4.10's "digest option" and §12.2's "notifications accumulate and a scheduled
     * job combines them into a single message".
     *
     * <p><strong>Both halves are built as of #244.</strong> A notification resolved to
     * this mode is written in state {@code HELD}, and {@code notification-digest} —
     * {@code NotificationDigestJob} over {@code DigestAssembly} — drains it: once a day at
     * a fixed local hour it groups everything held per recipient and channel, hands one
     * combined message to the channel's sender, and marks the rows sent.
     * {@code DigestWindow} is where the cadence is decided and argued.
     *
     * <p><strong>What is still true is that email and push have no transport.</strong>
     * #86 and #87 own those, both are registered as {@code UndeliverableChannelSender}, and
     * a digest handed to one reaches a log line rather than a person — exactly as an
     * immediate notification on those channels does today. That is a missing transport
     * rather than a missing digest, which is the distinction #244 was about: the holding no
     * longer accumulates for ever with nothing to drain it.
     *
     * <p>{@link DeliveryPolicy} still defaults nobody to this, and now for an ordinary
     * reason rather than a damning one — see {@link DeliveryPolicy#defaultFor}.
     */
    DIGEST
}
