package az.ideanest.notification.application;

import az.ideanest.notification.domain.NotificationChannel;

/**
 * The port: one channel, and the act of handing a message to it.
 *
 * <p><strong>#85 builds this interface and one real implementation. It does not build
 * the transports.</strong> Transactional email — templates, a provider, retries,
 * bounces, suppression lists — is #86, and push is #87. Neither is stubbed here, and
 * that is a deliberate refusal rather than an omission: a class called
 * {@code SmtpChannelSender} that logs and returns is a component that reports success
 * for messages nobody received, and every caller above it would then be correct in
 * believing the backer was told their payment failed.
 *
 * <p>So what exists is:
 *
 * <ul>
 *   <li>{@code InAppChannelSender}, which is genuinely complete — the notification row
 *       <em>is</em> the in-app message, so there is no transport to fail.
 *   <li>{@code UndeliverableChannelSender}, registered for email and push, which writes
 *       a line saying what would have been sent and to which issue, and marks the
 *       notification sent. Its own comment argues about which of the two wrong answers
 *       is less wrong; the choice is the same one {@code LoggingLaunchReminderNotifier}
 *       already made for the launch reminder, and it is made once, in one place, rather
 *       than by each caller.
 * </ul>
 *
 * <h2>The contract</h2>
 *
 * <p><strong>Returning normally means the channel accepted the message.</strong> It
 * does not mean it arrived and it certainly does not mean it was read — for a provider
 * those are facts that come back later over a webhook, and there is nowhere to put them
 * yet.
 *
 * <p><strong>Throwing means it did not.</strong> {@code NotificationDispatch} counts
 * the attempt, backs off, and eventually dead-letters — the same policy
 * {@code outbox_events} has, in the same columns. An implementation that swallows its
 * own failure will be recorded as having succeeded, which is worse than either outcome.
 *
 * <p><strong>The same message will be handed over twice.</strong> The send happens
 * before the transaction recording it commits, so a crash in between sends again; that
 * trade is argued in {@code NotificationDispatch} and it is the same one
 * {@code OutboxDispatch} makes. {@link NotificationMessage#id()} is stable across every
 * attempt and is the key an implementation must deduplicate on — by passing it to a
 * provider that supports idempotency keys, which is the only place the collapse can
 * honestly happen.
 *
 * <p>Implementations are found by {@link #channel()}. A {@code @Component} implementing
 * this is wired to its channel; nothing has to be registered anywhere else, and two
 * implementations claiming one channel fail at start-up rather than taking turns.
 */
public interface ChannelSender {

    /** Which of §4.10's three columns this implementation is. */
    NotificationChannel channel();

    /**
     * Hands the message to the channel.
     *
     * @throws RuntimeException when the channel did not take it. See the class comment:
     *     throwing is how a failure is reported, and it is the only way
     */
    void send(NotificationMessage message);

    /**
     * Hands a combined message to the channel — §12.2's digest.
     *
     * <p><strong>A second method rather than a digest of one, and rather than a default
     * implementation that loops.</strong> Both alternatives were available and both are
     * wrong in the same way: a digest is <em>one</em> message about several things, so
     * sending its members individually is precisely the behaviour the person who chose
     * digest mode asked not to have, and a port that let an implementation fall back to it
     * silently would make "digest" a preference the platform appears to honour and does not.
     * Making it abstract means #86 and #87 cannot ship a transport without deciding what a
     * digest of their channel looks like.
     *
     * <p>The contract is {@link #send(NotificationMessage)}'s, unchanged: returning means
     * the channel accepted it, throwing means it did not, and the same digest will be handed
     * over twice if the process dies between the two.
     * {@link NotificationDigest#id()} is the key that makes that collapsible, and it is
     * derived from the members rather than being a row identifier — that record explains
     * what follows from the difference.
     *
     * <p><strong>Never called for {@link NotificationChannel#IN_APP}.</strong>
     * {@code notification_preferences_in_app_does_not_digest} refuses the preference and
     * {@code notifications_in_app_is_not_held} refuses the row, so an inbox digest cannot be
     * constructed, let alone sent. {@code InAppChannelSender} implements this by refusing,
     * which is the honest body for a method that cannot be reached.
     *
     * @throws RuntimeException when the channel did not take it
     */
    void send(NotificationDigest digest);
}
