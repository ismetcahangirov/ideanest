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
     * <p><strong>The holding is built and the combining is not.</strong> A notification
     * resolved to this mode is written in state {@code HELD} and stays there: there is
     * no job that claims it, because a digest has to be rendered into one message and
     * handed to a transport, and the transports are #86 and #87. V26's header says the
     * same thing at greater length. It is why {@link DeliveryPolicy} defaults nobody to
     * this.
     */
    DIGEST
}
