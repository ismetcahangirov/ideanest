package az.ideanest.reward.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What the pledge module needs from a reward tier: its price, and one place on it.
 *
 * <p>The seam reservation (#51) reserves through. It is here rather than in
 * {@code reward.domain} or {@code reward.infrastructure} because those are this
 * module's internals and {@code ModuleBoundaryTests} refuses another module a view
 * of them — the application layer is the only part this module has agreed to keep
 * stable.
 *
 * <p><strong>Declared by the provider, unlike
 * {@code project.application.RewardFacts}</strong>, and the difference is not
 * taste. That interface is declared by the module that needs the answer because
 * the alternative would be a cycle. Here the alternative <em>is</em> the cycle:
 * {@code pledge.domain.QuotedLine} already names {@code ShippingRate} from this
 * package (#53), so the pledge module depends on this one, and an interface
 * declared over there and implemented here would close the loop —
 * {@code ModuleBoundaryTests} catches it in one line. One direction between two
 * modules, and this is the direction that already exists.
 *
 * <p><strong>Why the increment is not done here.</strong> §7.2 puts the stock
 * columns on {@code reward_tiers} and says they are "written by the pledge module
 * and by reservation, never by the campaign editor". Written by, not owned by: the
 * table belongs to the reward module, and a second module issuing statements
 * against it would be two modules sharing a table with one of them unable to see
 * the other's constraints. The seam is three methods wide instead, and each of
 * them is one statement.
 *
 * <p><strong>Two calls rather than one, deliberately.</strong> {@link #priceOf}
 * resolves the tier and {@link #reserveOnePlace} takes a place, and the split is
 * what lets a caller tell "there is no such tier on this campaign" — which is a
 * client that asked for the wrong thing — from "there is, and it is full", which
 * is §10.4's {@code REWARD_SOLD_OUT} and a different answer with different
 * alternatives in it. Collapsing them into one call that returns an empty result
 * for both would make those two indistinguishable at the only place that can tell
 * the backer which happened.
 *
 * <p><strong>#52 widened this seam by three methods, and by nothing else.</strong>
 * Checkout has to price a whole selection rather than one tier, offer something
 * else when a tier is full, and turn a held place into a claimed one. Each of the
 * three is a question about {@code reward_tiers}, so each is answered here rather
 * than by the pledge module reaching for the table — which is the arrangement this
 * interface exists to keep.
 */
public interface RewardStock {

    /**
     * What a place on this tier costs, if the tier is part of this campaign.
     *
     * <p>The campaign is part of the question rather than a check the caller makes
     * afterwards. A pledge names both, and a tier belonging to a different
     * campaign is not a tier this pledge can hold — the composite foreign key in
     * V17 refuses the row, and this is what makes the refusal answerable.
     *
     * @return empty when there is no such tier on that campaign
     */
    Optional<RewardTierPrice> priceOf(UUID projectId, UUID rewardTierId);

    /**
     * Takes one place on the tier, if there is one left.
     *
     * <p>One conditional {@code UPDATE}, which is the whole mechanism. The
     * statement takes PostgreSQL's row lock, re-reads the counts behind it, and
     * refuses itself when the tier is full — so two checkouts racing for the last
     * place are serialised by the database rather than by a check in Java that was
     * true when it ran. V7's {@code reward_tiers_stock_is_within_the_limit} is the
     * second line: if this statement is ever wrong, the transaction is refused
     * rather than the reward oversold.
     *
     * <p>A tier with no limit is unlimited and always has a place. The count is
     * still incremented, because §5.3 lets a creator add a limit later and the
     * floor it may be lowered to is the places already taken.
     *
     * @return false when the tier is full, or has gone since {@link #priceOf} saw
     *     it — both of which are "there is no place for this backer", which is
     *     what the caller has to tell them
     */
    boolean reserveOnePlace(UUID rewardTierId);

    /**
     * Gives one place back, when a reservation lapses or a draft is abandoned.
     *
     * <p>Guarded against going below zero rather than trusted: the count is what
     * stands between a limited tier and being oversold, and a release that ran
     * twice would create a place that does not exist. The constraint would refuse
     * a negative count, but the damage of a double release is done above zero,
     * where it is silent.
     *
     * @return false when there was nothing to give back
     */
    boolean releaseOnePlace(UUID rewardTierId);

    /**
     * Turns one held place into a claimed one, when a draft is confirmed.
     *
     * <p>The other half of {@link #reserveOnePlace}: the place stays taken, and what
     * changes is which column says so. Nothing is charged — §9.2 is explicit that no
     * money moves at confirmation — so this is a commitment and not a sale.
     *
     * @return false when this pledge was holding no place the tier knows about,
     *     which is an invariant violation rather than a race and is treated as one
     *     by the caller
     */
    boolean commitOnePlace(UUID rewardTierId);

    /**
     * Everything the backer selected, priced, with the rate for where it is going.
     *
     * <p>One call for the reward tier and every add-on together, because a checkout
     * quotes a selection and not a tier: asking per line would be one query per
     * add-on plus one per rate table, on the request a backer is waiting on with
     * their card out.
     *
     * <p><strong>What this does not decide.</strong> Whether a line is missing —
     * whether the campaign really has all of the tiers that were asked for — is left
     * to the caller, by omission: a tier that is not this campaign's is simply
     * absent from the answer, and the caller knows which identifiers it asked about.
     * That is the same split {@link #priceOf} draws, and for the same reason: only
     * the endpoint can tell the backer which of their selections went missing.
     *
     * @param destinationCountry ISO 3166-1 alpha-2, or null when the backer has not
     *     said. Null resolves no rates at all rather than defaulting to a country:
     *     {@code PledgeQuote} refuses a shipped line with no rate, which is the
     *     refusal a backer can act on, and quoting somewhere they did not name is
     *     the one that ends up on an invoice
     * @return one entry per tier that is genuinely this campaign's, in display order
     */
    List<SelectableTier> selectionOf(UUID projectId, Collection<UUID> rewardTierIds, String destinationCountry);

    /**
     * The tiers a backer could take instead, when the one they chose is full.
     *
     * <p>§10.4's {@code meta.availableAlternatives}. A sold-out refusal that names
     * nothing is a dead end at the exact moment somebody was trying to give money;
     * the same refusal with two tiers attached is a checkout that carries on.
     *
     * <p>What is offered is what a backer could actually select right now: this
     * campaign's public reward tiers, excluding add-ons — which are bought
     * <em>with</em> a reward and are not a substitute for one — excluding secret
     * tiers, which are reachable only through their own link, excluding any whose
     * availability window is not open, and excluding the ones with no places left.
     * Bounded by construction: §5.3 caps a campaign at a hundred tiers.
     *
     * @param at the moment the availability windows are judged against, from the
     *     injected {@code Clock}
     */
    List<UUID> alternativesTo(UUID projectId, UUID rewardTierId, Instant at);

    /**
     * A tier's price, which is an amount and the currency it is in.
     *
     * @param amount {@code numeric(14,2)}, never a float — this is the number a
     *     card is charged
     */
    record RewardTierPrice(UUID rewardTierId, BigDecimal amount, String currency) {
    }

    /**
     * One selected tier, in the terms a quote is built from.
     *
     * <p>Plain values rather than a {@code RewardTier}: that entity is this module's
     * and {@code ModuleBoundaryTests} keeps it here. What the pledge module needs is
     * a price, a currency, whether the thing is posted, and what posting it costs —
     * which is exactly {@code pledge.domain.QuotedLine}, and the mapping between the
     * two is a constructor call.
     *
     * @param shipped {@code ShippingType.isShipped()}, resolved here because the enum
     *     belongs to this module and a second copy of it there would be free to
     *     disagree about what {@code DIGITAL} means
     * @param shippingRate the rate for the destination the pledge named, or null
     *     when the creator has priced no such row. <strong>Null on a shipped line is
     *     a refusal and not a zero</strong>, and it is refused where the destination
     *     can be named — see {@code PledgeQuote}
     */
    record SelectableTier(
            UUID rewardTierId, BigDecimal amount, String currency, boolean shipped, ShippingRate shippingRate) {
    }
}
