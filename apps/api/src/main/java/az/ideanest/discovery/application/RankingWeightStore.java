package az.ideanest.discovery.application;

import az.ideanest.discovery.infrastructure.RankingWeightRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The weights the running application is scoring with, and how stale they may be.
 *
 * <h2>"Tunable without a deployment" is a statement about this class</h2>
 *
 * <p>§11.2 requires that a weight change reach the running service without a build.
 * There were three ways to do it and the choice is a trade against §20's thousand
 * requests a second:
 *
 * <ul>
 *   <li><strong>Read the table on every request.</strong> Correct, and it puts a
 *       thousand extra round trips a second per instance on the hot path of the feed —
 *       for nine rows that change a few times a month. Rejected on cost, not on
 *       principle.
 *   <li><strong>{@code LISTEN}/{@code NOTIFY}.</strong> Propagates in milliseconds, and
 *       needs a dedicated connection held outside the pool for the lifetime of the
 *       process, plus a reconnect path. Rejected on its failure mode: when the listener
 *       drops and does not come back, the instance serves the weights it had at start-up
 *       for ever and <em>nothing reports it</em>. Silent unbounded staleness is worse
 *       than bounded staleness, and it is worse in exactly the way that is hardest to
 *       notice — the feed still works.
 *   <li><strong>A bounded TTL, which is this.</strong> The worst case is stated,
 *       constant, and small, and there is nothing to reconnect.
 * </ul>
 *
 * <h2>The staleness window, stated rather than accidental</h2>
 *
 * <p><strong>Sixty seconds.</strong> A weight changed now is in force on every instance
 * within one minute. Deliberately the same window as {@code DiscoveryController}'s
 * {@code max-age} and the popularity sort's bucket: a feed page is already allowed to be
 * a minute out of date at every shared cache between here and the reader, so a ranking
 * change that took effect faster than sixty seconds would not be visible faster than
 * sixty seconds anyway. Making the window shorter would buy latency nobody can observe
 * and pay for it in queries per second.
 *
 * <p>What that costs: <strong>during the window, two instances can be scoring with
 * different weights.</strong> A reader whose two requests land on different instances
 * can therefore see two orderings — which is exactly the mid-scroll reshuffle
 * {@link RankingWeights#version()} exists for. The cursor is bound to the version, so
 * the second page is refused and restarted rather than served out of order. That is the
 * whole of the interaction between this cache and pagination, and it is why the version
 * is a digest of the weights rather than of the instant they were read.
 *
 * <p>{@link #refresh()} is the explicit half: the instance that takes a tuning request
 * re-reads immediately, so the person who made the change sees it on their next request
 * rather than up to a minute later. The other instances converge on the TTL. There is no
 * mechanism that promises they converge sooner, and none is claimed.
 */
@Service
public class RankingWeightStore {

    /** See the class comment. The same window the feed is cached for. */
    public static final Duration STALENESS_WINDOW = Duration.ofSeconds(60);

    private final RankingWeightRepository repository;
    private final Clock clock;

    /**
     * The snapshot and when it was taken, as one field.
     *
     * <p>One {@code volatile} reference to an immutable pair rather than two fields, so
     * a reader can never see a fresh snapshot beside a stale instant or the reverse.
     * Nothing is locked: two threads racing past the deadline both read the table and
     * both publish, and the loser's write is a snapshot of the same nine rows. A lock
     * would serialise every request on the feed's hot path to prevent one extra query a
     * minute.
     */
    private volatile Cached cached;

    public RankingWeightStore(RankingWeightRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * The weights to score this request with.
     *
     * <p>At most {@link #STALENESS_WINDOW} old. Loaded on first use rather than at
     * start-up: a bean that queried in its constructor would make the application fail
     * to start when the database is briefly unavailable, and a discovery service that
     * will not start is a worse outcome than one whose first request is a millisecond
     * slower.
     */
    @Transactional(readOnly = true)
    public RankingWeights current() {
        Cached snapshot = cached;
        Instant now = clock.instant();
        if (snapshot != null && now.isBefore(snapshot.expiresAt())) {
            return snapshot.weights();
        }
        return load(now);
    }

    /**
     * Re-reads the table now, and returns what it says.
     *
     * <p>Called by {@link RankingService} after a change, so that the instance which
     * accepted it is immediately consistent with it. Every other instance is consistent
     * within the window above.
     */
    @Transactional(readOnly = true)
    public RankingWeights refresh() {
        return load(clock.instant());
    }

    private RankingWeights load(Instant now) {
        RankingWeights weights = RankingWeights.of(repository.findAll());
        cached = new Cached(weights, now.plus(STALENESS_WINDOW));
        return weights;
    }

    private record Cached(RankingWeights weights, Instant expiresAt) {
    }
}
