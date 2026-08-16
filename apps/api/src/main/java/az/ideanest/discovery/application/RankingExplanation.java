package az.ideanest.discovery.application;

import az.ideanest.discovery.domain.RankingTerm;
import java.math.BigDecimal;
import java.util.List;

/**
 * Why one campaign scores what it scores. The moderator diagnostic of §11.2.
 *
 * <p><strong>A single opaque number cannot be tuned.</strong> §11.2 asks for ranking to
 * be measured rather than argued about, and a feed that answers "0.412" answers nothing:
 * the question somebody tuning has is whether that campaign is above another because of
 * its momentum, its badge, or the fact that it launched this morning, and no amount of
 * staring at two totals resolves it. This is the answer — every term, its raw value
 * before weighting, its weight, and the product — computed by the same expressions the
 * feed orders by, so an explanation cannot drift from the position it explains.
 *
 * <p><strong>Every term appears, including the five that contribute nothing.</strong> An
 * inert term reports a raw value and a contribution of zero and says what is blocking
 * it. That is the point: a reader of this endpoint learns that five eighths of §11.2 is
 * not running, which is a fact about the platform that a ranking with three silent terms
 * would hide.
 *
 * @param slug the campaign explained
 * @param title so the reader can tell they are looking at the campaign they meant
 * @param weightsVersion which snapshot of {@code ranking_weights} this was scored
 *     against, so two explanations taken either side of a tuning change can be told
 *     apart. The same value the cursor is bound to
 * @param total the composite, exactly as the feed's {@code ORDER BY} computes it
 * @param terms one entry per {@link RankingTerm}, in §11.2's order
 */
public record RankingExplanation(
        String slug, String title, String weightsVersion, BigDecimal total, List<Term> terms) {

    public RankingExplanation {
        terms = List.copyOf(terms);
    }

    /**
     * One term's arithmetic, spelled out.
     *
     * @param term which of §11.2's terms
     * @param active whether it is in the sum
     * @param blockedBy what has to land before it can be computed, or null
     * @param value the normalised term itself, in {@code [0, 1]}, or null when nothing
     *     can compute it. <strong>Null and zero are different</strong>: zero is a
     *     campaign that scores nothing on a term that works, null is a term that does
     *     not work
     * @param weight the configured multiplier, whether or not the term is active
     * @param contribution what this term added to the total — {@code ± weight × value},
     *     and exactly zero when the term is inactive
     */
    public record Term(
            RankingTerm term,
            boolean active,
            String blockedBy,
            BigDecimal value,
            BigDecimal weight,
            BigDecimal contribution) {
    }
}
