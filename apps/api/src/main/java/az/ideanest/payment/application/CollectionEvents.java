package az.ideanest.payment.application;

import az.ideanest.shared.money.Money;
import java.time.Instant;
import java.util.UUID;

/**
 * What the collection announces through §8.3's outbox (#64, #65).
 *
 * <p>Three events, recorded in the same transaction as the state change they describe,
 * so that a pledge cannot be collected without its backer being told and a backer
 * cannot be told about a collection that rolled back.
 *
 * <p><strong>The aggregate is the pledge</strong>, which gives §8.3's ordering
 * guarantee exactly where it is needed: a backer cannot be told their payment failed
 * after being told it succeeded, because the relay will not publish an event for a
 * pledge while an earlier pending one for the same pledge exists.
 *
 * <p>These are copies of a contract in the same sense {@code CampaignFinalisedEvent} is:
 * {@code NotificationEvents} declares its own reading of the same JSON, neither imports
 * the other, and the field names here are therefore the contract. Renaming one breaks
 * every consumer without breaking any compilation.
 */
public final class CollectionEvents {

    /**
     * Which kind of thing these happened to, and half of §8.3's ordering key.
     *
     * <p>The same word {@code pledge.confirmed} and {@code pledge.edited} are recorded
     * under, deliberately: a second aggregate name for the same rows would give a pledge
     * two independent orderings.
     */
    public static final String AGGREGATE_TYPE = "pledge";

    private CollectionEvents() {}

    /**
     * §6.2's {@code CHARGE_PENDING → COLLECTED}: the card was charged.
     *
     * <p>Recipient: the backer. §4.10's {@code PAYMENT_COLLECTED} row — the receipt for
     * money that has actually left their account, which for most backers arrives thirty
     * to sixty days after they pledged and is the first charge they see.
     *
     * @param amount what was taken, as §10.3's {@code {"amount", "currency"}} object with
     *     a string amount. <strong>Never a JSON number</strong>: a receipt that rounds
     *     somebody's charge is worse than no receipt
     * @param collectedAt when the charge was approved. On the event rather than read from
     *     the clock at delivery, because an event delivered an hour late describes
     *     something an hour old
     */
    public record PledgeCollected(
            UUID pledgeId, UUID projectId, UUID backerId, Money amount, Instant collectedAt) {

        public static final String EVENT_TYPE = "pledge.collected";
    }

    /**
     * §9.6: an attempt was refused, and there are more to come.
     *
     * <p>Recipient: the backer. §4.10 puts this row in bold and it deserves it — it is
     * the one notification whose absence costs somebody the thing they were trying to
     * buy.
     *
     * <p><strong>Not recorded for the first attempt.</strong> §9.6's table gives attempt
     * 1 no channel; {@code RetrySchedule#notifiesBacker} is where that is decided and
     * where the disagreement with §9.2's diagram is argued.
     *
     * @param attempt which of §9.6's attempts was refused, counted from one. In the
     *     rendering document rather than deciding the message: "we will try again" and
     *     "this is the last time" is {@link FinalPaymentWarning}, which is a different
     *     event because §4.10 gives it a different row
     * @param nextAttemptAt when the platform will try again. What makes the message
     *     actionable rather than alarming — a backer who is told "we will try again on
     *     Thursday" knows how long they have to change their card
     */
    public record PaymentFailed(
            UUID pledgeId,
            UUID projectId,
            UUID backerId,
            Money amount,
            int attempt,
            Instant nextAttemptAt,
            Instant failedAt) {

        /**
         * The event type the notification module has consumed since #85, unchanged.
         *
         * <p>It was declared before anything produced it, which is why this record and
         * {@code NotificationEvents.PaymentFailed} do not have the same fields: the
         * consumer reads {@code pledgeId}, {@code projectId}, {@code backerId},
         * {@code amount} and {@code attempt}, and ignores the two this side adds. That
         * asymmetry is the outbox contract working — {@code @JsonIgnoreProperties} on the
         * consumer is what makes a producer able to add a field without a coordinated
         * deployment.
         */
        public static final String EVENT_TYPE = "pledge.payment_failed";
    }

    /**
     * §9.6's fourth row: the last attempt was refused, and the pledge will be dropped.
     *
     * <p><strong>A separate event rather than a flag on {@link PaymentFailed}</strong>,
     * because §4.10 gives it a separate notification type — {@code FINAL_PAYMENT_WARNING}
     * — with a different body and a different preference. {@code NotificationEvents}
     * already said whose decision that is: "its producer is whoever knows the schedule",
     * and the schedule is {@code RetrySchedule}, here.
     *
     * @param attempt which attempt this was — the last one. Carried because §12.3's copy
     *     for {@code FINAL_PAYMENT_WARNING} already reads it ("we have tried {3} times"),
     *     and because "the last one" is a fact about the configured schedule rather than
     *     the number four
     * @param droppedAt when the pledge will be dropped if nothing changes: the end of
     *     §9.6's seven days. The one number that makes this message different from the
     *     three before it
     */
    public record FinalPaymentWarning(
            UUID pledgeId,
            UUID projectId,
            UUID backerId,
            Money amount,
            int attempt,
            Instant droppedAt,
            Instant failedAt) {

        public static final String EVENT_TYPE = "pledge.payment_final_warning";
    }
}
