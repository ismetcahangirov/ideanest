package az.ideanest.notification.application;

import az.ideanest.notification.NotificationProperties;
import az.ideanest.notification.domain.Notification;
import az.ideanest.notification.domain.NotificationChannel;
import az.ideanest.notification.infrastructure.NotificationRepository;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * One notification, one transaction: claim it, send it, record that it went.
 *
 * <p>{@code OutboxDispatch} is the same class for the same reason and every word of its
 * comment applies here. The short version:
 *
 * <p><strong>The claim is the lock.</strong> {@code FOR UPDATE SKIP LOCKED} inside the
 * statement that selects the row, so no second sender is looking at it, and several
 * replicas divide the queue between them rather than meeting on it. A claim written as
 * a read followed by an update cannot be made correct.
 *
 * <p><strong>Send, then commit — which is at-least-once and never at-most-once.</strong>
 * If the process dies between the channel accepting the message and this transaction
 * committing, the row is still {@code PENDING} and the message is sent again. That
 * duplicate is a deliberate choice: the other order — commit first, then send — turns
 * every crash into a notification nobody receives and nothing can tell afterwards that
 * anything was lost. A duplicate email is visible to the person who got it and
 * collapsible by a provider given an idempotency key; a lost one is visible to nobody.
 *
 * <p><strong>The key that makes it collapsible is {@code notifications.id}</strong>,
 * stable across every attempt and handed to the sender on the message.
 * {@link ChannelSender} states the contract; #86 and #87 have to honour it, and a
 * provider offering no idempotency key makes duplicate mail possible in a way this side
 * cannot fix.
 *
 * <p><strong>One row per transaction, not a batch.</strong> A rollback after twenty
 * successful sends would send all twenty again, and a commit covering rows whose send
 * failed would mark them sent. Round one row, the claim and the outcome are the same
 * unit of work — which is the invariant that matters: a row that says {@code SENT} was
 * handed to a channel, and a row that does not will be handed to it again.
 *
 * <p>A separate bean from {@code NotificationSender} rather than a method on it, because
 * Spring's {@code @Transactional} is a proxy and a self-call carries no transaction at
 * all — which, for an argument entirely about where the transaction boundary is, would
 * leave the boundary nowhere.
 */
@Component
public class NotificationDispatch {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatch.class);

    /**
     * How much of a failure is worth keeping. Long enough for a provider's error body,
     * short enough that a stack trace in a text column does not become the table.
     */
    private static final int LONGEST_RECORDED_ERROR = 1000;

    private final NotificationRepository notifications;
    private final NotificationProperties properties;
    private final Map<NotificationChannel, ChannelSender> senders;

    /**
     * @param senders every {@link ChannelSender} bean, indexed by the channel each
     *     claims. Injected as a list and indexed here rather than being looked up from
     *     the context, so that two implementations claiming one channel is a start-up
     *     failure naming both — and not two senders quietly taking turns
     */
    public NotificationDispatch(
            NotificationRepository notifications, NotificationProperties properties, List<ChannelSender> senders) {

        this.notifications = notifications;
        this.properties = properties;
        this.senders = index(senders);
    }

    private static Map<NotificationChannel, ChannelSender> index(List<ChannelSender> senders) {
        Map<NotificationChannel, ChannelSender> byChannel = new EnumMap<>(NotificationChannel.class);
        for (ChannelSender sender : senders) {
            ChannelSender existing = byChannel.put(sender.channel(), sender);
            if (existing != null) {
                throw new IllegalStateException("Two senders claim " + sender.channel() + ": " + existing + " and "
                        + sender + ". One channel has one implementation.");
            }
        }
        for (NotificationChannel channel : NotificationChannel.values()) {
            if (!byChannel.containsKey(channel)) {
                // Fail at start-up rather than on the first notification. A channel with
                // no sender is a queue that fills and never drains, and the symptom is
                // an absence — which is the hardest kind of fault to notice.
                throw new IllegalStateException("No sender is registered for " + channel);
            }
        }
        return byChannel;
    }

    /** What one pass over one notification did. */
    public enum Outcome {

        /** The channel accepted it, and the row says so. */
        SENT,

        /**
         * The channel refused it. The row is waiting for its next attempt, or is a dead
         * letter — because that was the last attempt, or because the channel refused with
         * a {@link PermanentDeliveryFailure} and there is no point in another.
         */
        FAILED,

        /**
         * There was nothing this sender could take: an empty queue, everything backing
         * off, or every candidate already locked by another replica. All three are the
         * same instruction — stop, and look again next tick.
         */
        NOTHING_TO_DO
    }

    /**
     * Sends the next eligible notification, if there is one this sender can have.
     *
     * @param now the instant to judge eligibility and backoff against. Passed in rather
     *     than read from a clock here so that a test can ask what happens after the
     *     backoff without waiting for it, and so that every row in one pass is judged
     *     against one moment
     */
    @Transactional
    public Outcome sendNext(Instant now) {
        return sendNext(now, senders);
    }

    /**
     * The same, to a given set of channels.
     *
     * <p>{@code OutboxDispatch.dispatchNext} takes its target for this reason and it is
     * the same one: a test about the retry policy needs a channel that refuses, and
     * replacing the bean would replace it for the whole suite and split the context cache
     * — which in this codebase means a second PostgreSQL container to prove something
     * about backoff. Production calls the overload above and gets the injected senders.
     *
     * @param senders what to hand each channel's messages to. Must cover the channels the
     *     queue can produce; a channel missing from it is a programming error rather than
     *     a delivery failure, and is refused as one rather than counted as an attempt
     */
    @Transactional
    public Outcome sendNext(Instant now, Map<NotificationChannel, ChannelSender> senders) {
        Notification notification = notifications.claimNext(now).orElse(null);
        if (notification == null) {
            return Outcome.NOTHING_TO_DO;
        }

        ChannelSender sender = senders.get(notification.getChannel());
        if (sender == null) {
            // Not recordFailure: burning an attempt and eventually dead-lettering a
            // person's notification because a caller passed an incomplete map would
            // charge the recipient for the caller's mistake.
            throw new IllegalStateException("No sender was given for " + notification.getChannel());
        }

        try {
            sender.send(NotificationMessage.of(notification));
        } catch (RuntimeException refused) {
            recordFailure(notification, now, refused);
            return Outcome.FAILED;
        }

        notification.sent(now);
        return Outcome.SENT;
    }

    /**
     * Counts the attempt and decides whether there is another one — which, for a
     * {@link PermanentDeliveryFailure}, is decided by the channel rather than by the
     * budget.
     *
     * <p>Caught rather than propagated, because the exception is the channel's answer
     * and not this transaction's failure: letting it out would roll back the very record
     * that says the attempt happened, and the notification would be retried immediately,
     * for ever, with {@code attempts} never moving off zero.
     */
    private void recordFailure(Notification notification, Instant now, RuntimeException failure) {
        int attempt = notification.getAttempts() + 1;
        String reason = describe(failure);

        if (failure instanceof PermanentDeliveryFailure) {
            // No backoff and no remaining attempts, because there is nothing to wait
            // for: the channel has said the message cannot be delivered to this
            // recipient at all. Retrying would spend the budget of a queue everything
            // else shares on a question that is already answered, and it would fill the
            // log with warnings about a transport that is working.
            notification.deadLetter(now, reason);
            // ERROR for the reason below, undiminished by the failure being expected:
            // this is still something the platform recorded as owed to a person and
            // will now never tell them.
            log.error("Notification {} cannot be delivered and is a dead letter: {}", notification, reason);
            return;
        }

        if (attempt >= properties.delivery().maxAttempts()) {
            notification.deadLetter(now, reason);
            // ERROR, and it should page somebody: this is something the platform
            // recorded as owed to a person and will now never tell them. §4.10 has
            // "payment failed" in bold, and this is the path on which that message is
            // lost.
            log.error(
                    "Notification {} is a dead letter after {} attempts: {}", notification, attempt, reason);
            return;
        }

        notification.retryAfter(now, properties.delivery().backoffAfter(attempt), reason);
        // WARN rather than ERROR: a refused send is the case this design exists to
        // absorb, and the notification is still the platform's outstanding promise.
        log.warn(
                "Notification {} was not accepted on attempt {} of {}; next attempt after {}: {}",
                notification,
                attempt,
                properties.delivery().maxAttempts(),
                notification.getNextAttemptAt(),
                reason);
    }

    /**
     * The failure, as a sentence somebody can act on.
     *
     * <p>The type and the message, not the stack: the stack is in the log for the
     * attempt that produced it, and what a dead letter needs to carry is which channel
     * said no and what it said.
     */
    private static String describe(RuntimeException failure) {
        String message = failure.getMessage();
        String described = failure.getClass().getSimpleName() + (message == null ? "" : ": " + message);
        return described.length() <= LONGEST_RECORDED_ERROR
                ? described
                : described.substring(0, LONGEST_RECORDED_ERROR);
    }
}
