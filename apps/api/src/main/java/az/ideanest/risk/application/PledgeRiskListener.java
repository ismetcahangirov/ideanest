package az.ideanest.risk.application;

import az.ideanest.shared.outbox.OutboxMessage;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * How the risk module hears that somebody backed a campaign — issue #108.
 *
 * <h2>A listener on the outbox, and the coupling is a string</h2>
 *
 * <p>The same arrangement {@code NotificationEventListener} uses, for the same reason:
 * {@code ApplicationEventOutboxDispatcher} stays untyped so that no per-event Java class
 * becomes a compile-time coupling between the relay and every module's events. Nothing in
 * this file imports anything from {@code az.ideanest.pledge}, which is checkable and is
 * checked by {@code ModuleBoundaryTests}.
 *
 * <h2>Why the event and not the request</h2>
 *
 * <p>Assessing inside the checkout would have one real advantage — the request's own source
 * address, rather than the session's most recent one — and three costs that outweigh it. It
 * would put four queries on the path of a person pressing a button; it would make the
 * pledge module depend on this one; and it would tempt the next person to make the score
 * decide something, which {@code RiskAssessments} argues at length that it must not.
 *
 * <p>The address is the honest cost of that choice, and {@code RiskFacts.addressAt} says so
 * where it is paid.
 *
 * <h2>IT NEVER THROWS</h2>
 *
 * <p>This runs inside the outbox relay's dispatch transaction, and that transaction is
 * shared with every other consumer of the same event. {@code OutboxDispatcher}'s contract
 * is explicit: a {@code RuntimeException} here leaves the row pending and the whole event
 * is delivered again — including the notification fan-out that already succeeded, which
 * would mean a backer told twice that their pledge was confirmed because a fraud score
 * could not be computed.
 *
 * <p>A fraud signal is not entitled to veto an event for the modules that share it. Every
 * failure here is a log line, at {@code WARN}, because an assessment that did not run is a
 * gap in a record somebody may go looking for.
 */
@Component
public class PledgeRiskListener {

    private static final Logger log = LoggerFactory.getLogger(PledgeRiskListener.class);

    /** The one event this module listens for. The vocabulary is {@code NotificationEvents}'. */
    private static final String PLEDGE_CONFIRMED = "pledge.confirmed";

    private final RiskAssessments assessments;
    private final ObjectMapper json;

    public PledgeRiskListener(RiskAssessments assessments, ObjectMapper json) {
        this.assessments = assessments;
        this.json = json;
    }

    @EventListener
    public void on(OutboxMessage message) {
        if (!PLEDGE_CONFIRMED.equals(message.eventType())) {
            return;
        }

        try {
            JsonNode payload = json.readTree(message.payload());
            UUID pledgeId = uuid(payload, "pledgeId");
            UUID projectId = uuid(payload, "projectId");
            UUID backerId = uuid(payload, "backerId");
            Instant confirmedAt = instant(payload);

            if (pledgeId == null || backerId == null || confirmedAt == null) {
                // A payload missing any of the three is one this build does not understand.
                // Said once rather than assessed against defaults: an assessment built on a
                // guessed backer is a record that names the wrong person.
                log.warn("A pledge.confirmed payload was not in the shape this build reads; no risk assessment.");
                return;
            }

            assessments.assessPledge(pledgeId, projectId, backerId, confirmedAt);
        } catch (RuntimeException failure) {
            /*
             * `RuntimeException` alone, and it covers the parse: Jackson 3's
             * `JacksonException` extends it, so naming both is a compile error rather
             * than belt and braces.
             *
             * See the class comment for why nothing is rethrown. The message names no
             * identifiers, because it is a line about this module failing rather than
             * about anybody's pledge.
             */
            log.warn("Could not assess a confirmed pledge for risk: {}", failure.toString());
        }
    }

    private static UUID uuid(JsonNode payload, String field) {
        JsonNode node = payload.get(field);
        if (node == null || !node.isString()) {
            return null;
        }
        try {
            return UUID.fromString(node.asString());
        } catch (IllegalArgumentException notAnIdentifier) {
            return null;
        }
    }

    private static Instant instant(JsonNode payload) {
        JsonNode node = payload.get("confirmedAt");
        if (node == null || !node.isString()) {
            return null;
        }
        try {
            return Instant.parse(node.asString());
        } catch (RuntimeException notAnInstant) {
            return null;
        }
    }
}
