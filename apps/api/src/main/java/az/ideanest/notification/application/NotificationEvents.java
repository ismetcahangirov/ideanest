package az.ideanest.notification.application;

import az.ideanest.shared.money.Money;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.UUID;

/**
 * The domain events this module listens for, as <em>this</em> module reads them.
 *
 * <h2>Read this before adding one</h2>
 *
 * <p><strong>Nothing publishes any of these yet.</strong> At the time #85 was built the
 * platform recorded no outbox event at all — {@code analytics.application.PledgeConfirmed}
 * says the same thing about the one event it consumes, at length — so the listener sees
 * no traffic and {@code notifications} stays empty until a producer exists. That is
 * stated here rather than implied, because a feature that looks finished and produces
 * nothing is worse than one that says what it is waiting for.
 *
 * <p>The remaining work per event is one call inside the transaction that already makes
 * the change: {@code outbox.record("pledge", pledgeId, "pledge.confirmed", …)} with the
 * fields below. It is not in this change because #85 owns the notification module and
 * {@code pledge} and {@code project} are somebody else's files to edit — doing it from
 * here would be the coupling {@code ModuleBoundaryTests} exists to prevent, arrived at
 * by convenience. #235 is adding the {@code pledge.confirmed} producer in parallel; this
 * listener keys on the string, so it starts working the moment that lands, with no
 * change here.
 *
 * <h2>These are copies, and they are supposed to be</h2>
 *
 * <p>{@code analytics.application.PledgeConfirmed} declares a record of the same name
 * for the same event, and the duplication is the design rather than a missed
 * refactoring. A shared event class would be a compile-time coupling between every
 * consumer and every producer — the thing {@code ApplicationEventOutboxDispatcher}
 * refuses to introduce when it keeps the dispatch untyped — and it would mean a
 * consumer that needs one more field forcing a rebuild on consumers that do not. What
 * is shared is the event-type string and the wire shape; each module owns its reading
 * of it.
 *
 * <h2>Unknown properties are ignored, deliberately</h2>
 *
 * <p>The opposite of {@code MoneyDeserializer}, which refuses them, and for the opposite
 * reason. There the sender is a client whose bug should be reported while it can still
 * be fixed; here the sender is another module in a rolling deployment, and an event
 * enriched by the newer release must not be undeliverable to the older one. §8.3's
 * expand-then-contract rule applied to a message instead of a column.
 *
 * <h2>A missing field is a fault, and it is loud</h2>
 *
 * <p>Every recipient identifier below is required. A payload without one is a payload
 * this module cannot act on, and {@code NotificationEventListener} throws rather than
 * skipping — see its failure section for why swallowing it would be worse.
 */
public final class NotificationEvents {

    private NotificationEvents() {
    }

    /**
     * §6.2's {@code DRAFT → CONFIRMED}: somebody backed a campaign.
     *
     * <p>Recipient: the backer. The one event in this file whose audience is
     * unambiguous — the person who did the thing is the person to tell.
     *
     * @param pledgeId which pledge. The subject the notification is about
     * @param projectId which campaign, carried so that a template can link to it without
     *     this module reading {@code pledges}
     * @param backerId who to tell
     * @param total what the pledge was worth, as §10.3's {@code {"amount", "currency"}}
     *     object. <strong>Never a JSON number</strong>, and it survives into
     *     {@code notifications.params} as the same object — a confirmation that rounds
     *     somebody's pledge is worse than no confirmation
     * @param confirmedAt when the transition happened. The instant the notification
     *     reports, and the reason it is on the event rather than taken from the clock:
     *     an event delivered an hour late describes something an hour old
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PledgeConfirmed(
            UUID pledgeId, UUID projectId, UUID backerId, Money total, Instant confirmedAt) {

        /** What the event is called in the vocabulary consumers switch on. */
        public static final String EVENT_TYPE = "pledge.confirmed";
    }

    /**
     * §4.5's PL-09: a backer changed what they had already pledged.
     *
     * <p>Recipient: the backer. <strong>Two channels and not three</strong> — §4.10
     * gives this row no push, because the person who made the change is holding the
     * phone the push would arrive on. {@code NotificationType.PLEDGE_EDITED} is where
     * that is expressed, and the fan-out never considers a channel a type does not have.
     *
     * @param total what it is worth now. The previous amount is deliberately absent: a
     *     notification says what is true, and "you changed it from X to Y" is a
     *     statement about two rows this module would have to be trusted to have read in
     *     the right order
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PledgeEdited(UUID pledgeId, UUID projectId, UUID backerId, Money total, Instant editedAt) {

        public static final String EVENT_TYPE = "pledge.edited";
    }

    /**
     * §9.4's collection: the card was refused.
     *
     * <p>Recipient: the backer. §4.10 puts this row in bold and it deserves it — it is
     * the one notification whose absence costs somebody the thing they were trying to
     * buy.
     *
     * @param amount what the platform tried to take. Money, and §10.3's rules apply
     * @param attempt which collection attempt this was, counted from one. In the
     *     rendering document rather than in the type, because "we will try again" and
     *     "this was the last attempt" is a difference in wording rather than in kind —
     *     {@code NotificationType.FINAL_PAYMENT_WARNING} is the separate row §4.10 gives
     *     the last one, and its producer is whoever knows the schedule
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PaymentFailed(
            UUID pledgeId, UUID projectId, UUID backerId, Money amount, int attempt, Instant failedAt) {

        public static final String EVENT_TYPE = "pledge.payment_failed";
    }

    /**
     * §4.3's funding progress crossing its goal.
     *
     * <p>Recipient: <strong>the creator and the campaign's backers.</strong> §4.10's row covers
     * both, and #85 could only express the first: the backers of a campaign are rows in
     * {@code pledges}, which belongs to the pledge module, and reading them from this one is
     * exactly the coupling {@code ModuleBoundaryTests} forbids. The two ways out were a producer
     * that carries the audience — an event with ten thousand identifiers in it, which is the
     * wrong shape for an event — and a port the pledge module publishes. #245 built the port, so
     * this payload still carries one identifier and the rest of the audience is asked for at
     * translation time. See {@code NotificationEventListener.backersOf}.
     *
     * <p>§12.1 also broadcasts this to everybody on the page over a WebSocket. That is a
     * different mechanism with no notification row behind it, there is no gateway in the service
     * yet, and it is not this module's.
     *
     * @param creatorId who to tell first. The one recipient the payload carries, and the one the
     *     audience bound never drops
     * @param goal the target that was reached, for the message. Money
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GoalReached(UUID projectId, UUID creatorId, Money goal, Instant reachedAt) {

        public static final String EVENT_TYPE = "project.goal_reached";
    }

    /**
     * §4.11's AD-01: moderation cleared a campaign for launch.
     *
     * <p>Recipient: the creator. Unambiguous, and it is the notification that turns a
     * queue somebody is waiting on into something they find out about without refreshing
     * a page.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProjectApproved(UUID projectId, UUID creatorId, Instant approvedAt) {

        public static final String EVENT_TYPE = "project.approved";
    }
}
