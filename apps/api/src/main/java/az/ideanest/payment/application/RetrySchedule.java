package az.ideanest.payment.application;

import az.ideanest.payment.PaymentProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * §9.6's table, as arithmetic (#65).
 *
 * <p>Four attempts and a window:
 *
 * <table border="1">
 *   <caption>§9.6, verbatim</caption>
 *   <tr><th>Attempt</th><th>Timing</th><th>Channel</th></tr>
 *   <tr><td>1</td><td>Immediately after close</td><td>—</td></tr>
 *   <tr><td>2</td><td>+24 hours</td><td>Email and push</td></tr>
 *   <tr><td>3</td><td>+72 hours</td><td>Email, push, in-app banner</td></tr>
 *   <tr><td>4</td><td>+5 days</td><td>Email, final warning</td></tr>
 *   <tr><td>—</td><td>+7 days</td><td>Pledge dropped</td></tr>
 * </table>
 *
 * <p><strong>The timings are measured from the campaign's close, not from the previous
 * attempt.</strong> That is what the table says — "+24 hours", "+72 hours", "+5 days"
 * are not intervals between rows, or the third would fall two days after the second
 * and the total would be over eight days rather than five. It also produces the
 * behaviour a backer expects: their four chances are at fixed moments after the
 * campaign ended, and a pass that ran late does not push the rest of their schedule
 * back with it.
 *
 * <p><strong>A class rather than a method on the properties</strong>, because the
 * arithmetic has three edges that each want a name and a test:
 *
 * <ul>
 *   <li>the schedule can run out before the window does — see {@link #nextAttemptAt};
 *   <li>the last attempt is the one §9.6 gives a different notification, and "which
 *       attempt is the last" is a fact about the list's length rather than the number 4;
 *   <li>a charge nobody has answered is not an attempt at all, and its recheck comes
 *       from a different property entirely.
 * </ul>
 */
@Component
public class RetrySchedule {

    private final PaymentProperties.Collection collection;

    public RetrySchedule(PaymentProperties properties) {
        this.collection = properties.collection();
    }

    /** §9.6's first row: the initial collection is due as soon as the campaign closes. */
    public Instant firstAttemptAt(Instant closedAt) {
        return closedAt.plus(collection.attemptDelays().get(0));
    }

    /** §9.6's last row: seven days after the close, the pledge is dropped. */
    public Instant windowEndsAt(Instant closedAt) {
        return closedAt.plus(collection.retryWindow());
    }

    /**
     * When the attempt after this one is due.
     *
     * <p><strong>Measured from the campaign's close</strong>, which the caller supplies
     * as the window's end minus the window's length — the pledge carries the frozen
     * window rather than the close, because V42 froze the thing that is a promise rather
     * than the thing that is a fact already on {@code projects}.
     *
     * <p><strong>When the schedule runs out, the answer is the end of the window.</strong>
     * Not null, and not the same as "there is no next attempt": V42 refuses a queued
     * pledge with no {@code next_charge_attempt_at}, so a pledge that has used all four
     * attempts must still carry one, and the honest value is the moment it will be
     * dropped. The drop sweep is what ends it, and the charge sweep will never pick it up
     * again because the attempt is not due until the moment the window closes — at which
     * point the drop sweep has a claim on it too, and both are guarded by the same row
     * lock.
     *
     * @param closedAt when the campaign closed
     * @param attemptsMade how many attempts have now been made, counted from one
     */
    public Instant nextAttemptAt(Instant closedAt, int attemptsMade) {
        List<Duration> delays = collection.attemptDelays();
        if (attemptsMade >= delays.size()) {
            return windowEndsAt(closedAt);
        }
        return closedAt.plus(delays.get(attemptsMade));
    }

    /**
     * When to ask the provider again about a charge it accepted and has not decided.
     *
     * <p>Not one of §9.6's attempts — see {@code Pledge#chargeUnresolved} — and measured
     * from now rather than from the close, because it is a wait on somebody else's system
     * rather than a slot in a policy.
     */
    public Instant recheckAt(Instant now) {
        return now.plus(collection.unresolvedRecheck());
    }

    /**
     * When the campaign closed, recovered from the window a pledge is carrying.
     *
     * <p>The pledge froze the end of its window and not the close, so this is the
     * subtraction that gets back to the moment the schedule is measured from. It is here
     * rather than at the call site so that the two — {@link #windowEndsAt} and this —
     * cannot drift apart into two different ideas of the same instant.
     */
    public Instant closedAtFrom(Instant windowEndsAt) {
        return windowEndsAt.minus(collection.retryWindow());
    }

    /**
     * Whether this attempt is the last one §9.6 gives.
     *
     * <p>What decides between §4.10's {@code PAYMENT_FAILED} row and its
     * {@code FINAL_PAYMENT_WARNING} one. Derived from the length of the configured
     * schedule rather than compared against 4, so that adding a fifth attempt does not
     * quietly leave the warning on the fourth.
     */
    public boolean isFinalAttempt(int attemptNumber) {
        return attemptNumber >= collection.maxAttempts();
    }

    /**
     * Whether a failure at this attempt is one §9.6 tells the backer about.
     *
     * <p><strong>The first attempt's row has no channel, and this method is where that
     * is honoured.</strong> It is worth being explicit that §9's two statements about
     * this disagree: §9.6's table gives attempt 1 a "—" in the channel column, while
     * §9.2's sequence diagram shows a "notify — update your card" immediately after the
     * first decline and <em>then</em> "four retries across seven days". The table is
     * followed, because it is the more specific artefact and the one that carries the
     * timings the rest of this class implements, and because attempt 2 is only
     * twenty-four hours behind attempt 1 — a backer is told, and told once rather than
     * twice about one card in a day. {@code docs/architecture.md} §9.6 records the
     * disagreement so that the next reader is not left to rediscover it.
     */
    public boolean notifiesBacker(int attemptNumber) {
        return attemptNumber > 1;
    }
}
