package az.ideanest.pledge;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Pledge settings.
 *
 * @param reservation how long a checkout may hold a limited reward's place, and
 *     how the places are given back
 */
@ConfigurationProperties(prefix = "ideanest.pledge")
public record PledgeProperties(Reservation reservation) {

    public PledgeProperties {
        // A deployment that configures nothing still starts, for the reason
        // ProjectProperties gives: a nested record binds to null when its whole
        // block is absent, and a null here would be a NullPointerException at the
        // first checkout rather than a configuration error at start-up.
        reservation = reservation == null ? Reservation.defaults() : reservation;
    }

    /**
     * The reservation window and the sweep that closes it.
     *
     * @param ttl how long a DRAFT pledge holds its place. <strong>Five minutes, and
     *     it is §4.5's number</strong> — "Reserve stock (5 min TTL)" in the pledge
     *     sequence — but it is configuration rather than a constant because it is a
     *     judgement with two costs on opposite sides. Too short and a backer who
     *     goes to find their card comes back to a sold-out tier they had already
     *     chosen; too long and one abandoned checkout keeps a place out of the
     *     market for as long as it lasts, which on a limited early-bird tier is the
     *     difference between a campaign's best hour and its worst. The right answer
     *     will come from watching real checkouts, and it should not need a
     *     deployment
     * @param cleanupSchedule §8.4's {@code reservation-cleaner}, every minute. A
     *     property so that the test profile can set it to {@code -} — Spring's own
     *     value for "do not schedule this" — and drive the sweep directly. A timer
     *     firing in the background of a test suite would claim the very rows a test
     *     is about to assert on, and this one fires every minute
     * @param cleanupBatchSize a bound on one pass, not a target. A campaign that
     *     closed with thousands of drafts in flight must not be one transaction;
     *     the sweep returns a minute later for the rest
     */
    public record Reservation(Duration ttl, String cleanupSchedule, int cleanupBatchSize) {

        /** §4.5's sequence diagram: "Reserve stock (5 min TTL)". */
        private static final Duration DEFAULT_TTL = Duration.ofMinutes(5);

        /** Every minute, which is what §8.4 says {@code reservation-cleaner} runs at. */
        private static final String DEFAULT_SCHEDULE = "0 * * * * *";

        private static final int DEFAULT_BATCH_SIZE = 200;

        static Reservation defaults() {
            return new Reservation(DEFAULT_TTL, DEFAULT_SCHEDULE, DEFAULT_BATCH_SIZE);
        }

        public Reservation {
            // Binding leaves an omitted property at its zero value, so an operator
            // who sets the schedule and not the TTL gets the documented default
            // rather than a reservation that has already expired when it is made.
            ttl = ttl == null ? DEFAULT_TTL : ttl;
            cleanupSchedule = cleanupSchedule == null || cleanupSchedule.isBlank()
                    ? DEFAULT_SCHEDULE
                    : cleanupSchedule;
            cleanupBatchSize = cleanupBatchSize == 0 ? DEFAULT_BATCH_SIZE : cleanupBatchSize;

            if (!ttl.isPositive()) {
                // A zero or negative TTL is every reservation lapsing the instant it
                // is taken: the sweep would release each place before the backer
                // reached the card form, and the symptom would be checkouts failing
                // at random rather than a configuration error.
                throw new IllegalArgumentException("A reservation TTL is a positive duration");
            }
            if (cleanupBatchSize < 1) {
                throw new IllegalArgumentException("A cleanup batch releases at least one reservation");
            }
        }
    }
}
