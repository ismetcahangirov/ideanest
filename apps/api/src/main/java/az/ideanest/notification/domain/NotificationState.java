package az.ideanest.notification.domain;

/**
 * Where one attempted delivery has got to.
 *
 * <p>The same vocabulary as {@code OutboxEventState}, on purpose: an operator at three
 * in the morning should not have to learn a second one. The extra value is
 * {@link #HELD}, which the outbox has no equivalent of.
 */
public enum NotificationState {

    /**
     * Written, not yet handed to its channel.
     *
     * <p>What the sender claims, {@code FOR UPDATE SKIP LOCKED}.
     */
    PENDING,

    /**
     * Waiting to be combined into a digest — {@link DeliveryMode#DIGEST}.
     *
     * <p><strong>Nothing drains this yet.</strong> V26's header and {@code DeliveryMode}
     * both say so, and it is the largest thing #85 leaves undone: a person who selects
     * digest mode accumulates rows here and receives nothing, because combining them
     * means rendering one message and handing it to a transport that does not exist
     * (#86, #87).
     */
    HELD,

    /**
     * Handed to its channel.
     *
     * <p>Not "read", and for email and push not even "arrived": it means the transport
     * accepted it. That is the strongest thing that can honestly be recorded on this
     * side of a provider.
     */
    SENT,

    /**
     * Abandoned after the attempts were exhausted, carrying the last error.
     *
     * <p>A question for an operator rather than a disappearance —
     * {@code notifications_dead_letters_say_why} insists on the reason and the count.
     */
    DEAD
}
