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
 * resolves the tier and {@link #reservePlaces} takes places, and the split is
 * what lets a caller tell "there is no such tier on this campaign" — which is a
 * client that asked for the wrong thing — from "there is, and it is full", which
 * is §10.4's {@code REWARD_SOLD_OUT} and a different answer with different
 * alternatives in it. Collapsing them into one call that returns an empty result
 * for both would make those two indistinguishable at the only place that can tell
 * the backer which happened.
 *
 * <p><strong>#203 gave the five stock methods a quantity, and took nothing
 * away.</strong> They said "one place" because a pledge names one reward tier
 * (§7.2), and an add-on is the same kind of row with §4.5's PL-04 quantity on it —
 * so holding one needs the same five moves against the same two columns, {@code n}
 * at a time. Five methods that take {@code n} are the five that took one, with the
 * arithmetic moved inside the statement where the row lock already is; a caller
 * that wants one passes one. The alternative — a second set of methods for add-ons
 * — would be two implementations of one invariant, free to disagree about the
 * expression V7's {@code reward_tiers_stock_is_within_the_limit} bounds.
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
     * Takes places on the tier, if it has that many left.
     *
     * <p>One conditional {@code UPDATE}, which is the whole mechanism. The
     * statement takes PostgreSQL's row lock, re-reads the counts behind it, and
     * refuses itself when the tier has too few — so two checkouts racing for the last
     * places are serialised by the database rather than by a check in Java that was
     * true when it ran. V7's {@code reward_tiers_stock_is_within_the_limit} is the
     * second line: if this statement is ever wrong, the transaction is refused
     * rather than the reward oversold.
     *
     * <p><strong>All of them or none.</strong> A backer who asked for three of an
     * add-on and could have two is refused, not sold two: they were quoted for three
     * and the creator was told to ship three. There is no partial hold to unwind.
     *
     * <p>A tier with no limit is unlimited and always has room. The count is
     * still incremented, because §5.3 lets a creator add a limit later and the
     * floor it may be lowered to is the places already taken.
     *
     * @param places how many. One for a reward tier, which is all §7.2 lets a pledge
     *     hold; §4.5's PL-04 quantity for an add-on
     * @return false when the tier has too few places left, or has gone since
     *     {@link #priceOf} saw it — both of which are "there is no place for this
     *     backer", which is what the caller has to tell them
     */
    boolean reservePlaces(UUID rewardTierId, int places);

    /**
     * Gives places back, when a reservation lapses or a draft is abandoned.
     *
     * <p>Guarded against going below zero rather than trusted: the count is what
     * stands between a limited tier and being oversold, and a release that ran
     * twice would create a place that does not exist. The constraint would refuse
     * a negative count, but the damage of a double release is done above zero,
     * where it is silent.
     *
     * @return false when the tier was not counting that many
     */
    boolean releasePlaces(UUID rewardTierId, int places);

    /**
     * Turns held places into claimed ones, when a draft is confirmed.
     *
     * <p>The other half of {@link #reservePlaces}: the places stay taken, and what
     * changes is which column says so. Nothing is charged — §9.2 is explicit that no
     * money moves at confirmation — so this is a commitment and not a sale.
     *
     * @return false when this pledge was holding fewer places than the tier knows
     *     about, which is an invariant violation rather than a race and is treated as
     *     one by the caller
     */
    boolean commitPlaces(UUID rewardTierId, int places);

    /**
     * Takes places directly as claimed ones, if the tier has that many left.
     *
     * <p><strong>For a {@code CONFIRMED} pledge that changes what it is buying
     * (#56).</strong> {@link #reservePlaces} holds places for a checkout that has not
     * finished; this one is for a backer who has already committed, so the places are
     * claimed from the moment they are taken. Reserving and then committing would
     * express one fact in two statements and would leave a reservation, briefly,
     * against a pledge that is not a draft — a row §8.4's sweep is looking for.
     *
     * @return false when the tier has too few places left, or has gone since it was
     *     priced — both of which are "there is no place for this backer"
     */
    boolean claimPlaces(UUID rewardTierId, int places);

    /**
     * Gives claimed places back, when a confirmed pledge is cancelled or changes what
     * it is buying.
     *
     * <p><strong>Not {@link #releasePlaces}, and the difference is the whole
     * point.</strong> A draft holds <em>reserved</em> places and a confirmed pledge
     * holds <em>claimed</em> ones, so giving back the wrong kind leaves the tier
     * counting places nobody holds while it is short of ones somebody does — and the
     * sum, which is what the limit is checked against, still looks correct.
     *
     * <p>Guarded against going below zero for {@link #releasePlaces}'s reason.
     *
     * @return false when the tier was not counting that many
     */
    boolean releaseClaimedPlaces(UUID rewardTierId, int places);

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
     * campaign's public tiers <em>of the same kind as the one that was refused</em>
     * — a reward instead of a reward, another add-on instead of an add-on, because
     * an add-on is bought <em>with</em> a reward and is not a substitute for one —
     * excluding secret tiers, which are reachable only through their own link,
     * excluding any whose availability window is not open, and excluding the ones
     * with no places left. Bounded by construction: §5.3 caps a campaign at a
     * hundred tiers.
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
     *     when the creator has priced no such row — by name or through a zone it
     *     falls into (#77). <strong>Null on a shipped line is a refusal and not a
     *     zero</strong>, and it is refused where the destination can be named — see
     *     {@code PledgeQuote}
     * @param unitWeightGrams what one of these weighs: the sum of its items'
     *     {@code weight_grams} times their quantities, which V7 said would be a query
     *     rather than a column and #77 is where that query runs. Zero when the
     *     creator recorded no weights, which is most campaigns and is not an error —
     *     a per-kilogram rate then contributes nothing and only the flat amount is
     *     charged
     */
    record SelectableTier(
            UUID rewardTierId,
            BigDecimal amount,
            String currency,
            boolean shipped,
            ShippingRate shippingRate,
            long unitWeightGrams) {
    }
}
