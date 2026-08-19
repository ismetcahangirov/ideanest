package az.ideanest.notification.application;

import az.ideanest.notification.NotificationProperties;
import az.ideanest.notification.domain.Notification;
import az.ideanest.notification.domain.NotificationChannel;
import az.ideanest.notification.infrastructure.HeldGroup;
import az.ideanest.notification.infrastructure.NotificationRepository;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * One digest, one transaction: pick a group, lock its held rows, send one message, record that
 * they went.
 *
 * <p><strong>The job #244 says does not exist.</strong> Until this class, a notification whose
 * resolved mode was {@code DIGEST} was written {@code HELD} and stayed there for ever:
 * {@code notification-sender} correctly refuses to claim a held row, and nothing else looked at
 * one. So choosing digest meant receiving nothing on that channel, permanently, with nothing
 * anywhere reporting a failure — which is worse than not offering the preference, because the
 * person believes they are subscribed.
 *
 * <p>{@link NotificationDispatch} is this class one message at a time and its reasoning is not
 * repeated in full. The short version, which is identical: the claim is the lock; send then
 * commit, which is at-least-once and never at-most-once, because the other order turns every
 * crash into a notification nobody receives and nothing afterwards can tell that anything was
 * lost; one message per transaction, so a rollback cannot re-send what already went.
 *
 * <h2>What is different, and why the lease matters here in a way it does not there</h2>
 *
 * <p><strong>The claim is two statements rather than one, and that is forced.</strong>
 * {@code NotificationDispatch} claims a row with {@code FOR UPDATE SKIP LOCKED} inside the
 * statement that selects it, which is what makes a read-then-update race impossible. A digest
 * is not a row — it is a grouping — and PostgreSQL will not lock the rows behind a
 * {@code GROUP BY}. So this asks which group to do next, and then locks that group.
 *
 * <p>Between those two statements another pass could pick the same group. What closes that
 * window is {@code JobLease}: {@code NotificationDigestJob} runs on the shared scheduler, so one
 * replica holds the job at a time. This is the same dependency {@code AnalyticsRollupJob}
 * declares — "on the shared scheduler, because this one genuinely needs the lease" — and it is
 * stated here rather than assumed, because a future reader moving this to a per-replica timer
 * would reintroduce a race that costs somebody a duplicate digest.
 *
 * <p><strong>{@code SKIP LOCKED} is still on the second statement</strong>, and it is a
 * containment measure rather than the correctness argument. If two passes ever did overlap —
 * a lease expiring under a slow run is the realistic way — the loser gets a short digest and
 * leaves the rest {@code HELD} and still due, which the next pass sends. That is a digest
 * arriving in two messages: worse than one, much better than a pass blocked on a row until its
 * own lease expires, and much better than a partial group being marked sent.
 *
 * <h2>A refused digest is charged to its members</h2>
 *
 * <p>The attempt count and the backoff live on the notification rows, because that is where the
 * schema puts them and a digest has nowhere else to keep them. So a refusal counts one attempt
 * against every member, moves them all to the same next attempt, and the group is retried as a
 * group. When the attempts are spent the members become dead letters individually — carrying the
 * same reason — which is what {@code notifications_dead_letters_say_why} asks for and what puts
 * them in {@code notifications_dead_idx} where an operator can find them.
 *
 * <p><strong>{@code next_attempt_at} is why V26 writes that column on a held row.</strong>
 * {@code Notification.held} said it was "the column a combining job would order by"; it turns
 * out to be the column a combining job backs off on, which is the same requirement seen from the
 * other side.
 */
@Component
public class DigestAssembly {

    private static final Logger log = LoggerFactory.getLogger(DigestAssembly.class);

    /**
     * How much of a failure is worth keeping. {@code NotificationDispatch}'s number, because it
     * is the same column.
     */
    private static final int LONGEST_RECORDED_ERROR = 1000;

    private final NotificationRepository notifications;
    private final NotificationProperties properties;
    private final Map<NotificationChannel, ChannelSender> senders;

    /**
     * @param senders every {@link ChannelSender} bean, indexed by channel. Indexed here rather
     *     than looked up, so that two implementations claiming one channel is a start-up failure
     *     naming both — {@code NotificationDispatch} does the same and for the same reason
     */
    public DigestAssembly(
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
        return byChannel;
    }

    /** What one attempt at one digest did. */
    public enum Outcome {

        /** The channel accepted it, and every row in it says so. */
        SENT,

        /**
         * The channel refused it. Every row in it has been charged an attempt and is either
         * waiting for the next one or, if that was the last, a dead letter.
         */
        FAILED,

        /**
         * There was nothing this pass could take: nothing held, nothing whose period has
         * closed, everything backing off, or a group another pass is holding. All of them are
         * the same instruction — stop, and look again next tick.
         */
        NOTHING_TO_DO
    }

    /**
     * Combines and sends the next digest that is due, if there is one.
     *
     * @param now the instant to judge eligibility and backoff against. Passed in rather than
     *     read from a clock here so that a test can ask what happens after a backoff without
     *     waiting for it, and so that every group in one pass is judged against one moment
     */
    @Transactional
    public Outcome combineNext(Instant now) {
        return combineNext(now, senders);
    }

    /**
     * The same, to a given set of channels.
     *
     * <p>{@link NotificationDispatch#sendNext(Instant, Map)} takes its target for this reason
     * and it is the same one: a test about the retry policy needs a channel that refuses, and
     * replacing the bean would replace it for the whole suite and split the context cache —
     * which in this codebase means a second PostgreSQL container to prove something about
     * backoff.
     *
     * @param senders what to hand each channel's digest to. Must cover the channels a held row
     *     can be on; a channel missing from it is a programming error rather than a delivery
     *     failure, and is refused as one rather than charged to somebody's notifications
     */
    @Transactional
    public Outcome combineNext(Instant now, Map<NotificationChannel, ChannelSender> senders) {
        Instant cutoff = properties.digest().window().lastClosedAt(now);

        List<HeldGroup> due = notifications.heldGroups(cutoff, now, PageRequest.of(0, 1));
        if (due.isEmpty()) {
            return Outcome.NOTHING_TO_DO;
        }
        HeldGroup group = due.get(0);

        List<Notification> members = notifications.claimHeld(
                group.recipientId(),
                group.channel().name(),
                cutoff,
                now,
                properties.digest().maxNotificationsPerMessage());

        if (members.isEmpty()) {
            // The group was there a statement ago and every row in it is locked, so another
            // pass has it. Reported as nothing to do rather than retried in place: retrying
            // would spin on the same group for the rest of the pass, and the rows are safe —
            // whoever holds them either sends them or leaves them held.
            log.debug("A digest {} is held by another pass; leaving it", group);
            return Outcome.NOTHING_TO_DO;
        }

        ChannelSender sender = senders.get(group.channel());
        if (sender == null) {
            // Not recordFailure: burning an attempt on somebody's notifications, and
            // eventually dead-lettering them, because a caller passed an incomplete map would
            // charge the recipient for the caller's mistake.
            throw new IllegalStateException("No sender was given for " + group.channel());
        }

        NotificationDigest digest = NotificationDigest.of(members);
        try {
            sender.send(digest);
        } catch (RuntimeException refused) {
            recordFailure(members, digest, now, refused);
            return Outcome.FAILED;
        }

        members.forEach(member -> member.sent(now));
        if (members.size() == properties.digest().maxNotificationsPerMessage()) {
            // The bound was reached, so this group probably has more waiting. Said out loud
            // rather than left to be inferred: a silent cap reads as "everything was
            // combined", and what actually happens is a second message on the next pass.
            log.info(
                    "Digest {} reached the bound of {} notifications; the rest of that group stays held and due",
                    digest,
                    properties.digest().maxNotificationsPerMessage());
        }
        return Outcome.SENT;
    }

    /**
     * Counts the attempt against every member and decides whether there is another one.
     *
     * <p>Caught rather than propagated, for {@code NotificationDispatch}'s reason: the exception
     * is the channel's answer and not this transaction's failure, and letting it out would roll
     * back the very record that says the attempt happened — leaving the digest retried
     * immediately, for ever, with {@code attempts} never moving off zero.
     *
     * <p>Every member gets the same verdict, because they were one message. A policy that
     * dead-lettered some of a group and retried the rest would produce a digest that silently
     * shrank on each attempt.
     */
    private void recordFailure(
            List<Notification> members, NotificationDigest digest, Instant now, RuntimeException failure) {

        int attempt = members.get(0).getAttempts() + 1;
        String reason = describe(failure);

        if (attempt >= properties.delivery().maxAttempts()) {
            members.forEach(member -> member.deadLetter(now, reason));
            // ERROR, and it should page somebody: these are messages the platform recorded as
            // owed to a person and will now never tell them, and there are several of them at
            // once.
            log.error(
                    "Digest {} is a dead letter after {} attempts, and so are the {} notifications in it: {}",
                    digest,
                    attempt,
                    digest.size(),
                    reason);
            return;
        }

        members.forEach(member ->
                member.retryAfter(now, properties.delivery().backoffAfter(attempt), reason));
        // WARN rather than ERROR: a refused send is the case this design exists to absorb, and
        // the notifications are still the platform's outstanding promise.
        log.warn(
                "Digest {} was not accepted on attempt {} of {}; next attempt after {}: {}",
                digest,
                attempt,
                properties.delivery().maxAttempts(),
                members.get(0).getNextAttemptAt(),
                reason);
    }

    /**
     * The failure, as a sentence somebody can act on.
     *
     * <p>The type and the message, not the stack — {@code NotificationDispatch} argues it, and
     * this writes the same column.
     */
    private static String describe(RuntimeException failure) {
        String message = failure.getMessage();
        String described = failure.getClass().getSimpleName() + (message == null ? "" : ": " + message);
        return described.length() <= LONGEST_RECORDED_ERROR
                ? described
                : described.substring(0, LONGEST_RECORDED_ERROR);
    }
}
