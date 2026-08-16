package az.ideanest.pledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import az.ideanest.pledge.application.PledgeAlreadyExistsException;
import az.ideanest.pledge.application.ReservationCleanerJob;
import az.ideanest.pledge.application.ReservationService;
import az.ideanest.pledge.application.RewardSoldOutException;
import az.ideanest.pledge.application.UnknownRewardTierException;
import az.ideanest.pledge.domain.Pledge;
import az.ideanest.shared.Identifiers;
import az.ideanest.support.AbstractIntegrationTest;
import az.ideanest.support.AdjustableClock;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Reservation, under the conditions it exists for.
 *
 * <p><strong>The test that carries the issue is
 * {@link #exactlyTheRemainingPlacesAreSold()}.</strong> Every other property of
 * reservation could be checked by calling a method and reading what it returned;
 * that one cannot, because the failure it is about only happens when two checkouts
 * read "one place left" at the same moment. So it runs real threads, through the
 * real service, against the real PostgreSQL container, and asserts that the number
 * of winners is exactly the number of places — the assertion that would have failed
 * under every implementation that checks and then writes.
 *
 * <p>Three things are asserted about that run, and they fail differently:
 *
 * <ul>
 *   <li>the count of successful reservations equals the places available — too many
 *       is an oversold reward, too few is a lost sale;
 *   <li>{@code claimed_quantity + reserved_quantity} equals the limit exactly, which
 *       is what V7's constraint bounds and what a backer's page reads;
 *   <li>the number of DRAFT pledges holding the tier equals
 *       {@code reserved_quantity}. The constraint cannot catch this one: a
 *       reservation that forgot to increment would satisfy every check in the
 *       database and would sell the same place twice.
 * </ul>
 *
 * <p>Time is the injected {@link AdjustableClock} rather than a sleep. A test that
 * waits five minutes for a TTL is a test nobody runs, and a test that shortens the
 * TTL to a second asserts a rule against a configuration nothing runs on.
 */
class ReservationTests extends AbstractIntegrationTest {

    /**
     * Distinguishes the accounts these tests create; see {@code PledgeSchemaTests}
     * for why it is a counter and not a slice of the identifier.
     */
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    /**
     * How many backers race for the last places.
     *
     * <p>Comfortably more than the connection pool, so that the threads genuinely
     * contend for the tier's row rather than queueing politely one at a time, and
     * comfortably more than the places on offer, so that most of them have to lose.
     */
    private static final int RACERS = 16;

    @Autowired
    private ReservationService reservations;

    @Autowired
    private ReservationCleanerJob cleaner;

    @Autowired
    private PledgeProperties properties;

    @Autowired
    private AdjustableClock clock;

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;

    private JdbcTemplate jdbc() {
        if (jdbc == null) {
            jdbc = new JdbcTemplate(dataSource);
        }
        return jdbc;
    }

    @AfterEach
    void clearPledgesAndReleaseTheClock() {
        jdbc().update("DELETE FROM pledges");
        jdbc().update("DELETE FROM reward_tiers");
        jdbc().update("DELETE FROM project_state_transitions");
        jdbc().update("DELETE FROM projects");
        // The clock is a bean the whole suite shares. A test that froze it and did
        // not let it go would stop every session, token, and story version after it.
        clock.reset();
    }

    // -----------------------------------------------------------------------
    // The point of the issue
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("sixteen backers race for eight places and exactly eight of them win")
    void exactlyTheRemainingPlacesAreSold() throws InterruptedException {
        UUID projectId = insertProject();
        UUID tierId = insertTier(projectId, 10);
        // Two places are already confirmed, so the arithmetic under test is
        // claimed + reserved against the limit and not reserved alone. This is what
        // #52's confirmation will write; nothing else does.
        jdbc().update("UPDATE reward_tiers SET claimed_quantity = 2 WHERE id = ?", tierId);

        Race race = raceForPlaces(projectId, tierId, backers(RACERS));

        // Anything other than a sold-out refusal is a bug: a constraint violation
        // would mean the tier oversold, a deadlock would mean the statement order is
        // wrong, and a timeout would mean a lock is held across something slow.
        assertThat(race.unexpected()).isEmpty();

        assertThat(race.reserved()).isEqualTo(8);
        assertThat(race.soldOut()).isEqualTo(RACERS - 8);

        // Exactly full, never over. V7's constraint would have refused a transaction
        // that went past it, which is why "over" cannot be observed here -- but "under"
        // could, and it would be a place nobody can buy.
        assertThat(reservedQuantity(tierId)).isEqualTo(8);
        assertThat(committedQuantity(tierId)).isEqualTo(10);

        // The count and the rows agree. No constraint checks this: a reservation that
        // took a place without recording it, or recorded one it never took, would pass
        // every check in the database and sell the same place twice.
        assertThat(draftsHolding(tierId)).isEqualTo(8);
    }

    @Test
    @DisplayName("an unlimited tier refuses nobody")
    void anUnlimitedTierNeverRefuses() throws InterruptedException {
        UUID projectId = insertProject();
        // Null limit is unlimited, which is the common case. The conditional update
        // has to let every one of these through rather than reading null as zero.
        UUID tierId = insertTier(projectId, null);

        Race race = raceForPlaces(projectId, tierId, backers(RACERS));

        assertThat(race.unexpected()).isEmpty();
        assertThat(race.soldOut()).isZero();
        assertThat(race.reserved()).isEqualTo(RACERS);

        // The count is still kept. §5.3 lets a creator add a limit later, and the
        // floor it may be lowered to is the places already taken.
        assertThat(reservedQuantity(tierId)).isEqualTo(RACERS);
    }

    // -----------------------------------------------------------------------
    // Time
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("a lapsed reservation is released by the sweep and its place is sold again")
    void anExpiredReservationGivesItsPlaceBack() {
        clock.freeze();
        UUID projectId = insertProject();
        UUID tierId = insertTier(projectId, 1);
        UUID first = insertBacker();
        UUID second = insertBacker();

        Pledge held = reservations.reserve(projectId, first, tierId);
        assertThat(reservedQuantity(tierId)).isEqualTo(1);
        // §4.5's five minutes, from the injected clock and not from a literal in the
        // service. Truncated to microseconds because that is what the column holds.
        assertThat(held.getReservationExpiresAt())
                .isEqualTo(clock.instant().truncatedTo(ChronoUnit.MICROS).plus(ttl()));

        // The tier is full while somebody is holding it. This is the refusal §10.4
        // answers as REWARD_SOLD_OUT.
        assertThatThrownBy(() -> reservations.reserve(projectId, second, tierId))
                .isInstanceOf(RewardSoldOutException.class);

        // Nothing has changed except the time.
        clock.advance(ttl().plusSeconds(1));
        assertThat(cleaner.releaseExpiredReservations(clock.instant())).isEqualTo(1);

        assertThat(state(held.getId())).isEqualTo("EXPIRED");
        // §6.2 has no timestamp of its own for a lapse, and this is the column that
        // means "the pledge stopped being active". See V17.
        assertThat(canceledAt(held.getId())).isNotNull();
        assertThat(reservedQuantity(tierId)).isZero();

        // And the place is genuinely back on sale, which is the whole point of
        // releasing it.
        assertThatCode(() -> reservations.reserve(projectId, second, tierId)).doesNotThrowAnyException();
        assertThat(reservedQuantity(tierId)).isEqualTo(1);
    }

    @Test
    @DisplayName("a backer whose own reservation lapsed can start again without waiting for the sweep")
    void aLapsedDraftDoesNotLockItsOwnBackerOut() {
        clock.freeze();
        UUID projectId = insertProject();
        UUID tierId = insertTier(projectId, 1);
        UUID backerId = insertBacker();

        Pledge first = reservations.reserve(projectId, backerId, tierId);

        // §7.2: one pledge per backer per campaign. While the first is live, a second
        // would be two places held by somebody who can only take one.
        assertThatThrownBy(() -> reservations.reserve(projectId, backerId, tierId))
                .isInstanceOf(PledgeAlreadyExistsException.class);

        clock.advance(ttl().plusSeconds(1));

        // Without the sweep having run. The backer is the person whose reservation
        // lapsed, and making them wait up to a minute to be allowed to try again would
        // be a refusal caused entirely by our own scheduling.
        Pledge second = reservations.reserve(projectId, backerId, tierId);

        assertThat(second.getId()).isNotEqualTo(first.getId());
        assertThat(state(first.getId())).isEqualTo("EXPIRED");
        // One released and one taken, not two held: the release and the reservation
        // are in one transaction.
        assertThat(reservedQuantity(tierId)).isEqualTo(1);
        assertThat(draftsHolding(tierId)).isEqualTo(1);
    }

    @Test
    @DisplayName("the sweep releases at most one batch and comes back for the rest")
    void theSweepIsBoundedByItsBatchSize() {
        clock.freeze();
        int batch = properties.reservation().cleanupBatchSize();
        UUID projectId = insertProject();
        UUID tierId = insertTier(projectId, null);

        for (UUID backerId : backers(batch + 1)) {
            reservations.reserve(projectId, backerId, tierId);
        }
        clock.advance(ttl().plusSeconds(1));

        // A campaign that closed with thousands of checkouts in flight must not be one
        // transaction. The bound is what stops a pass from overlapping its own next
        // tick, and the remainder is found a minute later.
        Instant now = clock.instant();
        assertThat(cleaner.releaseExpiredReservations(now)).isEqualTo(batch);
        assertThat(cleaner.releaseExpiredReservations(now)).isEqualTo(1);
        assertThat(cleaner.releaseExpiredReservations(now)).isZero();

        assertThat(reservedQuantity(tierId)).isZero();
    }

    @Test
    @DisplayName("a pledge confirmed before its window closed is not expired by the sweep")
    void theSweepLeavesConfirmedPledgesAlone() {
        clock.freeze();
        UUID projectId = insertProject();
        UUID tierId = insertTier(projectId, 1);

        Pledge held = reservations.reserve(projectId, insertBacker(), tierId);
        // What #52's confirmation will do. Written here because the endpoint does not
        // exist yet and the property being checked is the sweep's, not its.
        jdbc().update("UPDATE pledges SET state = 'CONFIRMED', confirmed_at = now() WHERE id = ?", held.getId());

        clock.advance(ttl().plusSeconds(1));

        // The sweep looks only at drafts. A pledge somebody has committed to must
        // never be expired out from under them by a timer, and its place must not be
        // handed to somebody else.
        assertThat(cleaner.releaseExpiredReservations(clock.instant())).isZero();
        assertThat(state(held.getId())).isEqualTo("CONFIRMED");
        assertThat(reservedQuantity(tierId)).isEqualTo(1);
    }

    // -----------------------------------------------------------------------
    // What is refused, and how the refusals differ
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("a tier that is not this campaign's is a different refusal from a full one")
    void anUnknownTierIsNotSoldOut() {
        UUID projectId = insertProject();
        UUID backerId = insertBacker();
        UUID theirTier = insertTier(insertProject(), 100);

        // Nothing to offer instead of a tier that does not exist, which is why this is
        // not REWARD_SOLD_OUT: §10.4's answer to that carries the alternatives that
        // are left.
        assertThatThrownBy(() -> reservations.reserve(projectId, backerId, Identifiers.newIdentifier()))
                .isInstanceOf(UnknownRewardTierException.class);

        // A tier under somebody else's campaign is the same mistake. V17's composite
        // foreign key would refuse the row; this is what makes the refusal answerable.
        assertThatThrownBy(() -> reservations.reserve(projectId, backerId, theirTier))
                .isInstanceOf(UnknownRewardTierException.class);

        assertThat(draftsHolding(theirTier)).isZero();
    }

    @Test
    @DisplayName("a pledge without a reward holds no place and still expires")
    void aPledgeWithoutARewardHoldsNothing() {
        clock.freeze();
        UUID projectId = insertProject();
        UUID tierId = insertTier(projectId, 1);

        // §4.5's PL-02: support without a reward. There is nothing limited to hold, so
        // nothing is reserved -- and the tier next to it is untouched.
        Pledge support = reservations.reserve(projectId, insertBacker(), null);

        assertThat(support.getRewardTierId()).isNull();
        assertThat(support.getTotalAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(reservedQuantity(tierId)).isZero();

        // It still lapses. An abandoned checkout that never expired would block its
        // backer from this campaign for ever, tier or no tier.
        assertThat(support.getReservationExpiresAt()).isNotNull();
        clock.advance(ttl().plusSeconds(1));
        assertThat(cleaner.releaseExpiredReservations(clock.instant())).isEqualTo(1);
        assertThat(state(support.getId())).isEqualTo("EXPIRED");
    }

    // -----------------------------------------------------------------------
    // The race
    // -----------------------------------------------------------------------

    /**
     * Every backer calls {@link ReservationService#reserve} at once, and what each of
     * them was told is counted.
     *
     * <p>The latch is what makes it a race. Submitting the tasks is not enough: a pool
     * that starts the first thread before the last one is queued would let the early
     * ones finish before the late ones begin, and the test would pass against an
     * implementation with no concurrency control at all.
     */
    private Race raceForPlaces(UUID projectId, UUID tierId, List<UUID> backers) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(backers.size());
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(backers.size());
        AtomicInteger reserved = new AtomicInteger();
        AtomicInteger soldOut = new AtomicInteger();
        List<Throwable> unexpected = Collections.synchronizedList(new ArrayList<>());

        try {
            for (UUID backerId : backers) {
                pool.execute(() -> {
                    try {
                        start.await();
                        reservations.reserve(projectId, backerId, tierId);
                        reserved.incrementAndGet();
                    } catch (RewardSoldOutException e) {
                        soldOut.incrementAndGet();
                    } catch (Throwable t) {
                        // Kept rather than logged: an exception swallowed inside a
                        // thread is a test that passes while the thing it is about
                        // is broken.
                        unexpected.add(t);
                    } finally {
                        finished.countDown();
                    }
                });
            }

            start.countDown();
            // Generous, because it is a deadlock detector rather than a performance
            // assertion: sixteen short transactions against a local container take
            // milliseconds, and anything approaching this means they are waiting on
            // each other.
            assertThat(finished.await(60, TimeUnit.SECONDS))
                    .withFailMessage("The reservations did not finish; something is holding a lock")
                    .isTrue();
        } finally {
            pool.shutdownNow();
        }

        return new Race(reserved.get(), soldOut.get(), List.copyOf(unexpected));
    }

    /** What sixteen simultaneous checkouts were told. */
    private record Race(int reserved, int soldOut, List<Throwable> unexpected) {
    }

    // -----------------------------------------------------------------------
    // Fixtures and readings
    // -----------------------------------------------------------------------

    private Duration ttl() {
        return properties.reservation().ttl();
    }

    private List<UUID> backers(int count) {
        List<UUID> backers = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            backers.add(insertBacker());
        }
        return backers;
    }

    private UUID insertBacker() {
        UUID id = Identifiers.newIdentifier();
        String marker = "reservation-" + SEQUENCE.incrementAndGet();
        jdbc().update(
                        "INSERT INTO users (id, email, name, slug) VALUES (?, ?::citext, ?, ?)",
                        id,
                        marker + "@example.com",
                        "Test Backer",
                        marker);
        return id;
    }

    private UUID insertProject() {
        UUID id = Identifiers.newIdentifier();
        jdbc().update(
                        "INSERT INTO projects (id, creator_id, slug, title) VALUES (?, ?, ?, ?)",
                        id,
                        insertBacker(),
                        "a-campaign-" + SEQUENCE.incrementAndGet(),
                        "A campaign");
        return id;
    }

    /**
     * @param limitQuantity null is unlimited, which is the common case. The column is
     *     left out of the statement rather than bound to null, so that the row is the
     *     one the editor would have written and the driver is not asked to guess the
     *     type of a null
     */
    private UUID insertTier(UUID projectId, Integer limitQuantity) {
        UUID id = Identifiers.newIdentifier();
        if (limitQuantity == null) {
            jdbc().update(
                            "INSERT INTO reward_tiers (id, project_id, title, amount) VALUES (?, ?, ?, ?)",
                            id,
                            projectId,
                            "An unlimited reward",
                            new BigDecimal("19.99"));
            return id;
        }
        jdbc().update(
                        """
                        INSERT INTO reward_tiers (id, project_id, title, amount, limit_quantity)
                        VALUES (?, ?, ?, ?, ?)
                        """,
                        id,
                        projectId,
                        "A limited reward",
                        new BigDecimal("19.99"),
                        limitQuantity);
        return id;
    }

    private int reservedQuantity(UUID tierId) {
        return read("SELECT reserved_quantity FROM reward_tiers WHERE id = ?", tierId);
    }

    private int committedQuantity(UUID tierId) {
        return read("SELECT claimed_quantity + reserved_quantity FROM reward_tiers WHERE id = ?", tierId);
    }

    private int draftsHolding(UUID tierId) {
        return read("SELECT count(*) FROM pledges WHERE reward_tier_id = ? AND state = 'DRAFT'", tierId);
    }

    private int read(String sql, UUID id) {
        Integer value = jdbc().queryForObject(sql, Integer.class, id);
        return value == null ? 0 : value;
    }

    private String state(UUID pledgeId) {
        return jdbc().queryForObject("SELECT state FROM pledges WHERE id = ?", String.class, pledgeId);
    }

    private Instant canceledAt(UUID pledgeId) {
        return jdbc().queryForObject("SELECT canceled_at FROM pledges WHERE id = ?", Instant.class, pledgeId);
    }
}
