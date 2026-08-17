package az.ideanest.pledge.application;

import az.ideanest.pledge.domain.Pledge;
import az.ideanest.pledge.domain.PledgeAddon;
import az.ideanest.pledge.infrastructure.PledgeAddonRepository;
import az.ideanest.pledge.infrastructure.PledgeRepository;
import az.ideanest.reward.application.RewardStock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * One lapsed draft, one transaction: the pledge becomes EXPIRED and every place it was
 * holding goes back to the tier that counted it.
 *
 * <p><strong>Every place, which since #203 means the add-ons too.</strong> This swept
 * the reward tier's single place and knew nothing about {@code pledge_addons}, which
 * was correct while nothing incremented an add-on's counters and became the other half
 * of #203's bug the moment something did: an abandoned checkout would have held a
 * limited add-on's stock for ever, and no constraint in V7 can see stock that is held
 * and never released — the count would simply be wrong, quietly, for as long as the
 * campaign ran. So the lines are read and walked here, in {@link HeldPlaces}' order,
 * inside the same transaction as the state change.
 *
 * <p><strong>This class exists because the transaction boundary has to be around
 * one reservation.</strong> A batch in one transaction has the wrong failure mode
 * in both directions, for the reason {@code LaunchReminderDelivery} gives about a
 * batch of notices: a rollback after twenty successful releases leaves twenty
 * tiers already credited, and a commit covering rows that failed would mark drafts
 * expired whose places were never given back. Around a single row both disappear,
 * because the state change and the credit are then the same unit of work — which
 * is the invariant that actually matters here. A draft that says EXPIRED while
 * {@code reserved_quantity} still counts it is a place nothing will ever release.
 *
 * <p>It is a separate bean rather than a method on {@link ReservationCleanerJob}
 * because Spring's {@code @Transactional} is a proxy and a self-call would be no
 * transaction at all — which is precisely the arrangement this exists to prevent.
 *
 * <p><strong>Claim, then credit, inside the transaction.</strong> The conditional
 * update is what makes two replicas' sweeps safe (§8.4: every replica runs its own
 * timer until #134). Exactly one caller's statement matches, and only that caller
 * goes on to credit the tier; the others find the row already settled and do
 * nothing. Crediting first and claiming afterwards would give the place back twice
 * whenever two sweeps overlapped, and the tier would then sell a place it does not
 * have.
 *
 * <p><strong>{@code REQUIRED}, not {@code REQUIRES_NEW}.</strong> Both callers
 * want this joined to whatever they are doing: the sweep has no transaction of its
 * own, so one is started here, and {@link ReservationService} calls it while
 * settling the caller's own stale draft — where the release and the reservation
 * that follows it should stand or fall together. A new transaction there would
 * commit a release for a checkout that then failed.
 */
@Component
public class ReservationExpiry {

    private static final Logger log = LoggerFactory.getLogger(ReservationExpiry.class);

    private final PledgeRepository pledges;
    private final PledgeAddonRepository addons;
    private final RewardStock stock;

    public ReservationExpiry(PledgeRepository pledges, PledgeAddonRepository addons, RewardStock stock) {
        this.pledges = pledges;
        this.addons = addons;
        this.stock = stock;
    }

    /**
     * Expires one draft whose reservation has run out, if nobody has already.
     *
     * <p>Takes the instant rather than reading the clock, so that a test can ask
     * what happens after five minutes without waiting five minutes, and so that
     * every row in one sweep is judged against one moment rather than against a
     * clock that moved while the batch was running.
     *
     * @return true when this call is the one that expired it. False means the row
     *     had gone, the backer confirmed first, or another replica's sweep got
     *     there — none of which is an error, and all of which leave the row settled
     */
    @Transactional
    public boolean release(UUID pledgeId, Instant now) {
        Pledge pledge = pledges.findById(pledgeId).orElse(null);
        if (pledge == null || !pledge.isDraft()) {
            return false;
        }

        // Read before the claim: the claim is a bulk update that clears the
        // persistence context, so this instance is detached the moment it runs and
        // what it was holding would have to be read again. The add-on lines are read
        // here for the same reason and not merely for tidiness -- they are rows of
        // another table, but they are read through the same persistence context.
        List<PledgeAddon> heldAddons = addons.findByPledge(pledgeId);
        SortedMap<UUID, Integer> held = HeldPlaces.heldBy(pledge, heldAddons);

        if (pledges.expireLapsedDraft(pledgeId, now) == 0) {
            // Somebody else settled it between the read above and this statement:
            // another replica's sweep, or the backer confirming. Two instances
            // running one timer is the normal state of affairs, and this is where
            // that is made safe rather than merely tolerated.
            return false;
        }

        for (Map.Entry<UUID, Integer> line : held.entrySet()) {
            if (!stock.releasePlaces(line.getKey(), line.getValue())) {
                // The draft held places on a tier and the tier had nothing to give
                // back. That is an invariant violation rather than a race — this
                // transaction is the one that just claimed the row — so it is logged
                // loudly and the pledge still expires. Leaving it DRAFT would hold
                // places that the count says do not exist, which is the worse of the
                // two.
                log.error(
                        "Reservation {} held {} places on reward tier {} that the tier had no record of.",
                        pledgeId,
                        line.getValue(),
                        line.getKey());
            }
        }
        return true;
    }
}
