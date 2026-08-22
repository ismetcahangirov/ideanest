package az.ideanest.payment.application;

import az.ideanest.pledge.application.ChargeablePledge;
import az.ideanest.pledge.application.PledgeCollection;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * One pledge dropped, in its own transaction: §9.6's last row (#65).
 *
 * <p>A separate bean rather than a method on {@link ChargeRetryJob}, for
 * {@code ReservationExpiry}'s reason: Spring's {@code @Transactional} is a proxy and a
 * self-call would be no transaction at all — which is precisely the per-pledge boundary
 * this exists to guarantee. One pledge that will not drop must not roll back the
 * hundred the pass has already ended.
 */
@Service
public class CollectionDrop {

    private static final Logger log = LoggerFactory.getLogger(CollectionDrop.class);

    private final PledgeCollection pledges;

    public CollectionDrop(PledgeCollection pledges) {
        this.pledges = pledges;
    }

    /**
     * Ends one pledge, if it is still this pass's to end.
     *
     * <p>The identifier came from an unlocked read, so the row is re-judged under its own
     * lock: a pledge collected in the second between the two must not then be dropped,
     * and {@code PledgeCollection#claimForDropping} is what refuses that.
     *
     * <p><strong>No notification, and that is deliberate.</strong> §9.6 gives the drop no
     * channel. The backer was told at the fourth attempt that it was the last one, with
     * the date; a second message five days later saying the thing they were warned about
     * has happened only repeats bad news to somebody who already decided not to act on
     * it.
     *
     * <p><strong>No place is given back either.</strong> {@code Pledge#dropped} carries
     * the argument: the tier's remaining count is a fact about a campaign that has
     * closed, and crediting it would make a sold-out tier look available on a page nobody
     * can pledge from.
     *
     * @return whether this call is the one that dropped it
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean drop(UUID pledgeId, Instant now) {
        Optional<ChargeablePledge> claimed = pledges.claimForDropping(pledgeId, now);
        if (claimed.isEmpty()) {
            return false;
        }
        pledges.recordDropped(pledgeId, now);
        log.info("Pledge {} was dropped after §9.6's window elapsed.", pledgeId);
        return true;
    }
}
