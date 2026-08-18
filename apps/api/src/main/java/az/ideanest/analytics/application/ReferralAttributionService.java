package az.ideanest.analytics.application;

import az.ideanest.analytics.domain.LastNonDirectTouch;
import az.ideanest.analytics.domain.ReferralAttribution;
import az.ideanest.analytics.domain.ReferralChannel;
import az.ideanest.analytics.domain.ReferralSource;
import az.ideanest.analytics.domain.ReferralTouch;
import az.ideanest.analytics.infrastructure.ReferralAttributionRepository;
import az.ideanest.analytics.infrastructure.ReferralTouchRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The second half of attribution: turning a confirmed pledge into one line of §4.7's
 * CD-03 report.
 *
 * <p>The rule is {@code LastNonDirectTouch} and it lives there, tested without a
 * database. What lives here is everything around it — which visits are even
 * candidates, what happens when there are none, and what happens when the same event
 * arrives twice.
 *
 * <h2>Written in the delivery's transaction. {@code MANDATORY}, deliberately</h2>
 *
 * <p>{@code OutboxDispatch} claims an event, publishes it, and records that it went —
 * all in one transaction. Joining that transaction is what makes "the event was
 * delivered" and "the attribution exists" one fact: a failure here rolls the dispatch
 * back too, the row stays {@code PENDING}, and the event is retried. The alternatives
 * are the ones {@code AuditLog} enumerates. {@code REQUIRES_NEW} would leave an
 * attribution behind for a delivery that then failed and will be replayed;
 * {@code REQUIRED} would quietly open a transaction for a caller that had none and
 * commit on its own, which is the same divergence reached by not saying so.
 *
 * <h2>Redelivery</h2>
 *
 * <p>At-least-once is {@code OutboxMessage}'s stated contract, not an edge case: a
 * crash between the transport accepting a message and the relay committing republishes
 * it. So the first thing this does is ask whether the pledge is already attributed,
 * and {@code referral_attributions_pledge_key} is what makes the answer true under
 * concurrency rather than merely usually right.
 *
 * <h2>Every confirmed pledge produces a row</h2>
 *
 * <p>Including the ones nobody referred, which are recorded as
 * {@link ReferralSource#direct()}. A report that only counted attributable pledges
 * would show shares of a total that is not the campaign's, and "42% from Instagram"
 * would mean 42% of the part we happened to be able to explain — which is the single
 * most misleading number a marketing report can contain.
 */
@Service
public class ReferralAttributionService {

    private static final Logger log = LoggerFactory.getLogger(ReferralAttributionService.class);

    /**
     * How many of a backer's visits are read before the rule is applied.
     *
     * <p>A bound, not a target: the query returns them most recent first, and the rule
     * takes the most recent that qualifies, so the answer is unchanged by anything
     * below this line unless every one of the last fifty visits was direct. A backer
     * with more than fifty visits to one campaign inside the window has a browser
     * extension or a script, and reading all of it would be a page of memory spent to
     * reach the same conclusion.
     */
    private static final int EVIDENCE_READ = 50;

    private final ReferralTouchRepository touches;
    private final ReferralAttributionRepository attributions;
    private final Clock clock;

    public ReferralAttributionService(
            ReferralTouchRepository touches, ReferralAttributionRepository attributions, Clock clock) {

        this.touches = touches;
        this.attributions = attributions;
        this.clock = clock;
    }

    /**
     * Attributes one confirmed pledge, or does nothing because it already is.
     *
     * @param eventId the delivery this is being written from —
     *     {@code OutboxMessage.id()}, which is stable across every redelivery of the
     *     same event
     * @return the attribution, whether it was written now or by an earlier delivery.
     *     Optional only because a pledge already attributed is not re-read: the caller
     *     is a listener with nothing to do either way, and loading the existing row to
     *     hand it back would be a query per duplicate for nobody
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<ReferralAttribution> attribute(UUID eventId, PledgeConfirmed pledge) {
        Objects.requireNonNull(eventId, "An attribution names the delivery it came from");
        Objects.requireNonNull(pledge, "There is no pledge to attribute");

        if (attributions.existsByPledgeId(pledge.pledgeId())) {
            // The ordinary redelivery. Logged at debug rather than warn: it is the
            // contract working, not a fault.
            log.debug("Pledge {} is already attributed; delivery {} changes nothing", pledge.pledgeId(), eventId);
            return Optional.empty();
        }

        Instant pledgedAt = pledge.confirmedAt();
        Optional<ReferralTouch> winner = LastNonDirectTouch.of(evidenceFor(pledge), pledgedAt);
        ReferralSource source = winner.map(ReferralTouch::getSource).orElseGet(() -> fallbackFor(pledge));

        ReferralAttribution attribution = ReferralAttribution.record(
                pledge.pledgeId(),
                pledge.projectId(),
                winner.map(ReferralTouch::getId).orElse(null),
                source,
                pledge.total(),
                pledgedAt,
                clock.instant().truncatedTo(ChronoUnit.MICROS),
                eventId);

        return Optional.of(attributions.save(attribution));
    }

    /**
     * The visits that could explain this pledge: this backer's, on this campaign,
     * inside their windows, and not made after the pledge itself.
     *
     * <p>Empty for a backer with no account attached to any visit, which is every
     * backer who never reached the capture endpoint — a client that does not call it,
     * a device where the token was cleared, an application that has not been updated.
     * That is the case {@link #fallbackFor} exists for.
     */
    private List<ReferralTouch> evidenceFor(PledgeConfirmed pledge) {
        if (pledge.backerId() == null) {
            return List.of();
        }
        return touches.findEvidence(
                pledge.projectId(),
                pledge.backerId(),
                pledge.confirmedAt(),
                PageRequest.of(0, EVIDENCE_READ));
    }

    /**
     * What to say when no visit qualifies: the code the pledge itself carries, or
     * direct.
     *
     * <p><strong>This is what makes the feature work for the traffic that never
     * touched the capture endpoint.</strong> §4.5's checkout already accepts a
     * {@code referrerCode} and V17 already stores it, so a creator's share link
     * followed straight into a pledge — no visit recorded, no token held — is still a
     * link that earned something. Ignoring it would report those pledges as direct and
     * tell the creator their links do not work.
     *
     * <p>It is a fallback and not a preference: a recorded visit beats it, because a
     * visit is dated evidence about this backer and a code on a pledge is only a
     * parameter that survived to checkout. A visit from the newsletter yesterday and a
     * stale code in the checkout URL is a pledge the newsletter earned.
     */
    private static ReferralSource fallbackFor(PledgeConfirmed pledge) {
        String code = pledge.referrerCode();
        if (code == null || code.isBlank()) {
            return ReferralSource.direct();
        }
        return ReferralSource.of(ReferralChannel.REFERRAL_LINK, null, null, code);
    }
}
