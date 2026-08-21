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
     * §5.1 applied at the deadline: the campaign funded.
     *
     * <p>Recipient: <strong>the creator and the campaign's backers.</strong> The same audience
     * as {@link GoalReached} and resolved the same way, through {@code shared.audience}; what
     * differs is that this one is final. "Goal reached" is news about a campaign that is still
     * running, and a campaign can reach its goal and then have a backer cancel; this is the
     * message that says the money is going to be taken.
     *
     * <p><strong>The two outcomes are two event types rather than one with a flag</strong>, and
     * {@code project.application.CampaignFinalisedEvent} argues why on the producing side. What
     * it buys here is that the branch which chooses between §4.10's two rows is the same
     * {@code switch} that recognises the event at all, so there is no way to route a succeeded
     * event into an unsuccessful message.
     *
     * @param goal what the campaign had to raise. Money, and §10.3's rules apply
     * @param pledged what it raised, <strong>frozen at the deadline</strong> — V29's
     *     {@code outcome_pledged_amount} rather than the live total. It matters here more than
     *     anywhere: this notification may be delivered, or redelivered, long after collections
     *     have started failing, and a message that reported the live total would tell a backer
     *     their campaign raised less than the campaign it just said succeeded
     * @param backersCount how many people were behind that total, for the message
     * @param finalisedAt when the campaign closed. Not when this was delivered
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CampaignSucceeded(
            UUID projectId, UUID creatorId, Money goal, Money pledged, Integer backersCount, Instant finalisedAt) {

        public static final String EVENT_TYPE = "project.succeeded";
    }

    /**
     * §5.1 applied at the deadline: the campaign did not fund.
     *
     * <p>Recipient: <strong>the creator and the campaign's backers.</strong> Both, and the
     * backers are not optional politeness — they are holding a commitment that is now never
     * going to be charged, and §5.1 has the platform delete their stored card within thirty
     * days. Somebody who is not told will either expect a charge that never arrives or, worse,
     * see one from an unrelated campaign and attribute it to this one.
     *
     * <p>The same six fields as {@link CampaignSucceeded}, deliberately: the difference between
     * the two messages is entirely in the wording, and a payload that dropped {@code pledged}
     * because the campaign failed would make "you raised 8,400 ₼ of your 10,000 ₼ goal"
     * unwritable — which is the sentence a creator most needs to see.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CampaignUnsuccessful(
            UUID projectId, UUID creatorId, Money goal, Money pledged, Integer backersCount, Instant finalisedAt) {

        public static final String EVENT_TYPE = "project.unsuccessful";
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

    /**
     * A campaign opened — §4.9's C-11 and §4.10's "followed creator launched".
     *
     * <p>Recipient: <strong>everybody following the creator</strong>, and nobody else. Not the
     * creator, who pressed the button; not the campaign's backers, because it has none yet.
     *
     * <p>The audience is resolved through {@code shared.audience} exactly as
     * {@link GoalReached}'s is, and it is the audience #245 could not express until #90 built
     * {@code follows}. {@code ProjectAudience.FOLLOWERS} is asked for the campaign and answered
     * by the community module, which joins through the campaign's creator; this payload
     * therefore carries the creator and not a list.
     *
     * <p><strong>Not the launch reminder.</strong> §4.10 has a separate row, "reminder: project
     * launched", for the people who registered on a pre-launch page — a different audience, a
     * different table, and a sweep of its own in the project module. A follower and a reminder
     * are different promises and somebody may hold both; that they then receive two messages is
     * correct, because they asked twice.
     *
     * @param creatorId whose campaign it is, and the account whose followers are the audience
     * @param deadline when it closes, so the message can say how long there is
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProjectLaunched(UUID projectId, UUID creatorId, Instant launchedAt, Instant deadline) {

        public static final String EVENT_TYPE = "project.launched";
    }

    /**
     * A live campaign crossed one of §4.10's deadline thresholds.
     *
     * <p>Recipients: <strong>three of §4.10's rows come out of this one event</strong>, and the
     * split is the interesting part.
     *
     * <ul>
     *   <li>"48 hours remaining" and "24 hours remaining" go to the creator and the campaign's
     *       backers. A backer is somebody with a commitment already made, and what a deadline
     *       notice offers them is the last chance to change it or to tell somebody else.
     *   <li>"Saved project ending soon" goes to the people who saved the campaign and have
     *       <strong>not</strong> backed it — {@code ProjectAudience.SAVERS} minus
     *       {@code BACKERS}. That subtraction is the whole reason the two are separate rows
     *       in §4.10: the message to somebody who has not pledged is an invitation, and sending
     *       an invitation to somebody who already pledged reads as though their pledge was not
     *       noticed.
     *   <li>It is sent at the 48-hour threshold only. A saver is being invited, not chased, and
     *       §4.10 gives them one row rather than two.
     * </ul>
     *
     * <p><strong>One event type carrying the threshold, rather than two event types.</strong>
     * {@code project.application.CampaignEndingSoonEvent} argues it on the producing side: the
     * two thresholds are the same message with a different number, where the two campaign
     * outcomes are genuinely different messages. Where the difference does matter — §4.10 gives
     * the 48-hour row an email column and the 24-hour row none — it is a property of
     * {@code NotificationType}, which is where that table lives.
     *
     * @param hoursRemaining the threshold crossed: 48 or 24. <strong>The threshold, not a live
     *     remainder</strong>, so a redelivery hours later still describes the message the
     *     platform decided to send
     * @param endsAt when the campaign closes, which is the fact a reader can act on
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CampaignEndingSoon(
            UUID projectId, UUID creatorId, Integer hoursRemaining, Instant endsAt, Instant crossedAt) {

        public static final String EVENT_TYPE = "project.ending_soon";
    }
}
