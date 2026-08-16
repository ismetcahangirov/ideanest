package az.ideanest.pledge.application;

import az.ideanest.pledge.PledgeProperties;
import az.ideanest.pledge.domain.Pledge;
import az.ideanest.pledge.domain.PledgeState;
import az.ideanest.pledge.infrastructure.PledgeRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Holds a limited reward's place for the backer who is checking out, for as long
 * as §4.5 gives them to finish.
 *
 * <p><strong>The reservation is a DRAFT pledge.</strong> §6.2 already says so —
 * {@code [*] -> DRAFT}, and {@code DRAFT -> EXPIRED: reservation TTL} — so what
 * this class creates is a row in {@code pledges} whose existence is the claim on
 * the stock. V17's header carries the argument for why that lives in PostgreSQL
 * rather than in the Redis key §4.5's capability table mentions; the short version
 * is that taking the place and recording who took it have to be one transaction,
 * and across two datastores they are not.
 *
 * <p><strong>What makes it correct is one statement, not this class.</strong>
 * {@link RewardStock#reserveOnePlace} is a conditional {@code UPDATE} that takes
 * PostgreSQL's row lock and re-reads the counts behind it, so two checkouts racing
 * for the last place are serialised by the database. Everything here is ordering
 * and error messages. If this code is wrong, V7's
 * {@code reward_tiers_stock_is_within_the_limit} refuses the transaction rather
 * than letting the tier oversell — which is the property {@code ReservationTests}
 * exercises with real threads against a real PostgreSQL, because it is the only
 * way to find out.
 *
 * <p><strong>The order of the three writes is deliberate.</strong> The caller's
 * own stale draft is settled first, then the place is taken, then the pledge is
 * inserted. Taking the place before inserting means a failure of the insert rolls
 * the place back inside the same transaction; inserting first would leave a window
 * in which a draft exists that holds nothing, and it is the drafts that the sweep
 * counts.
 *
 * <p><strong>What is not here.</strong>
 *
 * <ul>
 *   <li><strong>The endpoint.</strong> {@code POST /v1/pledges/draft}, its request
 *       and response types, and §10.3's {@code Idempotency-Key} are #52. The
 *       column and its unique index exist; nothing here writes them.
 *   <li><strong>Whether the campaign will take a pledge at all.</strong> A
 *       reservation on a campaign that is not live, or one past its deadline
 *       without late pledging enabled, is a request that should never reach this
 *       far. That is a question about {@code projects}, which is another module's
 *       state machine, and it belongs with the endpoint that has already loaded
 *       the campaign to render it (#52). Checking it here would mean this module
 *       depending on the project module in order to say no twice.
 *   <li><strong>Pricing beyond the tier.</strong> Add-ons, shipping, and tax are
 *       §4.5's PL-04 to PL-06 and land with the checkout that collects a
 *       destination. The draft carries the tier's price and zeroes, so the
 *       generated total is a real number from the moment the row exists.
 * </ul>
 */
@Service
public class ReservationService {

    /**
     * The only currency the platform settles in, for a pledge that has no reward
     * tier to take one from.
     *
     * <p>Duplicated from {@code RewardService}, and deliberately not shared, for
     * the reason stated there: the real answer is the campaign's own
     * {@code currency} column, which this module cannot read. When a second
     * currency exists, both constants are replaced by that lookup. A pledge that
     * <em>does</em> name a tier takes the currency off the tier, which is where the
     * price came from.
     */
    private static final String SUPPORTED_CURRENCY = "AZN";

    private final PledgeRepository pledges;
    private final RewardStock stock;
    private final ReservationExpiry expiry;
    private final PledgeProperties properties;
    private final Clock clock;

    public ReservationService(
            PledgeRepository pledges,
            RewardStock stock,
            ReservationExpiry expiry,
            PledgeProperties properties,
            Clock clock) {
        this.pledges = pledges;
        this.stock = stock;
        this.expiry = expiry;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Reserves a place on a reward tier and returns the draft pledge holding it.
     *
     * <p>The draft lapses at {@code now + ttl} and is released by §8.4's
     * {@code reservation-cleaner} — see {@link ReservationCleanerJob}. Nothing has
     * been charged and no card has been seen; that is confirmation's job (#52).
     *
     * @param rewardTierId null for §4.5's PL-02, a pledge that supports the
     *     campaign without taking a reward. Nothing is reserved in that case,
     *     because there is no limited thing to hold — the draft still expires, so
     *     that an abandoned checkout does not block the backer from starting
     *     another one for ever
     * @throws UnknownRewardTierException when the campaign has no such tier
     * @throws RewardSoldOutException when it has no places left — §10.4's
     *     {@code REWARD_SOLD_OUT}
     * @throws PledgeAlreadyExistsException when this backer already has a live
     *     pledge on this campaign. §7.2: one per backer per project
     */
    @Transactional
    public Pledge reserve(UUID projectId, UUID backerId, UUID rewardTierId) {
        // Truncated to what the column can hold: PostgreSQL stores microseconds, so
        // an expiry written at nanosecond precision comes back as a different
        // instant, and this one is compared against by a sweep.
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);

        settleAnyExistingPledge(projectId, backerId, now);

        BigDecimal amount = BigDecimal.ZERO;
        String currency = SUPPORTED_CURRENCY;
        if (rewardTierId != null) {
            RewardStock.RewardTierPrice price = stock.priceOf(projectId, rewardTierId)
                    .orElseThrow(() -> new UnknownRewardTierException(projectId, rewardTierId));
            amount = price.amount();
            currency = price.currency();

            if (!stock.reserveOnePlace(rewardTierId)) {
                // Somebody took the last place, possibly while this backer was
                // reading the page. §10.4 answers this with the alternatives that
                // are left rather than with a bare refusal.
                throw new RewardSoldOutException(rewardTierId);
            }
        }

        Instant expiresAt = now.plus(properties.reservation().ttl());
        return pledges.save(Pledge.draft(projectId, backerId, rewardTierId, amount, currency, expiresAt));
    }

    /**
     * Deals with the pledge this backer already has on this campaign, if any.
     *
     * <p>Two outcomes, and the difference matters to whoever is standing at the
     * checkout. A pledge that is still live — a draft with time left, or a
     * confirmed pledge — is §7.2's one-per-backer rule and they are told about it.
     * A draft whose five minutes have run out is released here and now, rather than
     * when the sweep next runs: the backer is the person whose reservation it was,
     * and making them wait up to a minute to be allowed to try again would be a
     * refusal caused entirely by our own scheduling.
     *
     * <p>Racing another request that is doing the same thing is safe in both
     * branches. The release is a conditional update that exactly one caller wins,
     * and if this read misses a pledge that another transaction is inserting,
     * {@code pledges_project_backer_active_key} refuses the second insert. This
     * read decides what to say; the index decides what is true.
     */
    private void settleAnyExistingPledge(UUID projectId, UUID backerId, Instant now) {
        Optional<Pledge> existing = pledges.findActive(projectId, backerId, PledgeState.ACTIVE);
        if (existing.isEmpty()) {
            return;
        }

        Pledge pledge = existing.get();
        if (!pledge.hasLapsed(now)) {
            throw new PledgeAlreadyExistsException(projectId, backerId, pledge.getId(), pledge.getState());
        }
        expiry.release(pledge.getId(), now);
    }
}
