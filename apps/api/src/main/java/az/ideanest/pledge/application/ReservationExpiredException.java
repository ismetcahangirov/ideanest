package az.ideanest.pledge.application;

import java.time.Instant;
import java.util.UUID;

/**
 * The draft's five minutes ran out before it was confirmed. §10.4's
 * {@code RESERVATION_EXPIRED}.
 *
 * <p>Refused on the strength of {@code reservation_expires_at} rather than of the
 * state, because §8.4's sweep runs every minute and the backer does not: a draft
 * whose window closed forty seconds ago is still {@code DRAFT} in the table, and
 * confirming it would commit a place the tier has already promised to give back.
 * The clock decides, and the sweep tidies up.
 *
 * <p><strong>Deliberately not expired here.</strong> The obvious tidy-up — mark it
 * {@code EXPIRED} and release its place on the way out — cannot work: this refusal
 * rolls its transaction back, and the release would roll back with it, leaving a
 * pledge marked expired in nobody's memory. The sweep does it within the minute,
 * which is what the sweep is for.
 *
 * <p>The expiry is carried so the client can say when it lapsed rather than only
 * that it did.
 */
public class ReservationExpiredException extends RuntimeException {

    private final UUID pledgeId;
    private final Instant expiredAt;

    public ReservationExpiredException(UUID pledgeId, Instant expiredAt) {
        super("The reservation on pledge " + pledgeId + " lapsed at " + expiredAt);
        this.pledgeId = pledgeId;
        this.expiredAt = expiredAt;
    }

    public UUID pledgeId() {
        return pledgeId;
    }

    public Instant expiredAt() {
        return expiredAt;
    }
}
