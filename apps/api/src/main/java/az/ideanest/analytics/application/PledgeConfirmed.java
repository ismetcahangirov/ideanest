package az.ideanest.analytics.application;

import az.ideanest.shared.money.Money;
import java.time.Instant;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The {@code pledge.confirmed} event, as this module reads it.
 *
 * <h2>Read the note before changing anything here</h2>
 *
 * <p><strong>Nothing publishes this event yet.</strong> At the time #94 was built the
 * pledge module recorded no outbox event at all —
 * {@code PledgeService.confirm} performs §6.2's {@code DRAFT → CONFIRMED} and
 * announces nothing — so the listener below has no traffic in production and the
 * attribution table stays empty until it does. That is stated here rather than
 * implied, because a feature that looks finished and produces nothing is worse than
 * one that says what it is waiting for.
 *
 * <p>The remaining work is one call inside the transaction that already exists:
 * {@code outbox.record("pledge", pledgeId, "pledge.confirmed", …)} with the fields
 * below. It is not in this change because #94 owns the analytics module and the pledge
 * module is somebody else's file to edit; doing it from here would be the coupling
 * {@code ModuleBoundaryTests} exists to prevent, arrived at by convenience.
 *
 * <h2>Why a payload with values in it, when {@code Outbox} argues for identifiers</h2>
 *
 * <p>{@code Outbox}'s guidance is "enough to route on, and no more — the consumer
 * reads what it needs inside its own transaction". That is right, and it assumes the
 * consumer <em>can</em> read what it needs. This one cannot: the pledge's amount lives
 * in {@code pledges}, which belongs to another module, and reaching for it would be
 * the boundary violation again. So the two facts attribution cannot compute — what the
 * pledge was worth and when it was confirmed — travel with the event.
 *
 * <p>The cost is the one {@code Outbox} names, and it is small here rather than
 * absent: an amount in a payload is financial data with a different retention rule
 * from the row it came from. It is an amount and a currency, with no account, no
 * address, and no card, and it is the minimum that makes the report possible at all.
 *
 * <h2>Unknown properties are ignored, deliberately</h2>
 *
 * <p>The opposite of {@code MoneyDeserializer}, which refuses them, and for the
 * opposite reason. There the sender is a client whose bug should be reported while it
 * can still be fixed; here the sender is another module in a rolling deployment, and
 * an event enriched by the newer release must not be undeliverable to the older one.
 * §8.3's expand-then-contract rule applied to a message instead of a column.
 *
 * @param pledgeId which pledge. The key an attribution is unique on, so that a
 *     redelivery writes nothing twice
 * @param projectId which campaign. Carried rather than looked up, for the reason
 *     above, and it is also what scopes the visits the rule may consider
 * @param backerId who pledged. <strong>Used to find their visits and then discarded</strong>
 *     — {@code ReferralAttribution} has nowhere to store it, which is what keeps a
 *     creator's report from naming the person behind a source
 * @param total what the pledge was worth, as §10.3's {@code {"amount", "currency"}}
 *     object. Never a JSON number
 * @param referrerCode the code the pledge itself carries, when it has one:
 *     {@code pledges.referrer_code}, which §4.5's checkout already accepts. The
 *     fallback when a backer has no visit on record — a link followed on a device
 *     that never reached the capture endpoint still credits the link
 * @param confirmedAt when §6.2's transition happened. <strong>The instant the rule is
 *     applied against</strong>, and the reason this is on the event rather than taken
 *     from the clock: an event delivered an hour late, or retried tomorrow, has to
 *     produce the answer it would have produced on time
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PledgeConfirmed(
        UUID pledgeId,
        UUID projectId,
        UUID backerId,
        Money total,
        String referrerCode,
        Instant confirmedAt) {

    /** What the event is called in the vocabulary consumers switch on. */
    public static final String EVENT_TYPE = "pledge.confirmed";
}
