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
     * <p>Drained by {@code notification-digest}, which is #244's work: once a day at a fixed
     * local hour it groups every held row per recipient and channel, hands one message to the
     * channel's sender, and moves the whole group to {@link #SENT}.
     * {@code notifications_held_idx} is the index it claims by, and {@code DigestWindow} is
     * where "once a day at a fixed local hour" is decided.
     *
     * <p><strong>A third starting state rather than a step on the way.</strong> A digest is
     * held from the moment it is written; nothing moves {@code PENDING} to here.
     *
     * <p>{@code next_attempt_at} is written on a held row and is not decoration: a digest that
     * a channel refuses backs off, and every row in it carries the same next attempt.
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
