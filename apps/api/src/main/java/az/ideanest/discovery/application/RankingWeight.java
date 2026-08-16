package az.ideanest.discovery.application;

import az.ideanest.discovery.domain.RankingTerm;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * One row of {@code ranking_weights}: a term of §11.2, and how much it counts.
 *
 * @param term which of §11.2's terms this is
 * @param weight the multiplier, as {@code BigDecimal} and never a {@code double}. It is
 *     not money, but the composite it multiplies is a keyset cursor's sort key and is
 *     compared for exact equality — see {@code RelevanceScore}
 * @param active whether the term is in the sum at all. <strong>Not the same as a weight
 *     of zero</strong>: zero says "measured, and currently counts for nothing"; inactive
 *     says "not computed". They look identical in a feed and mean different things to
 *     somebody tuning, so they are two columns and the diagnostic reports them apart
 * @param blockedBy what has to land before this term can be computed, or null when the
 *     data exists today. V15 refuses to let a blocked term be active, so this is the
 *     whole of the answer to "why is this contributing nothing"
 * @param description what the term measures, for whoever is tuning it
 * @param updatedAt when it was last changed; the audit of <em>who</em> and <em>why</em>
 *     is {@code ranking_weight_changes}
 */
public record RankingWeight(
        RankingTerm term,
        BigDecimal weight,
        boolean active,
        String blockedBy,
        String description,
        Instant updatedAt) {

    /**
     * What this term actually contributes per unit, which is zero unless it is active.
     *
     * <p>Here rather than at each call site so that "an inactive term contributes
     * exactly nothing" has one implementation. The SQL builder additionally omits the
     * addend altogether, which is the stronger form of the same rule; this is what the
     * diagnostic and the tests read.
     */
    public BigDecimal effectiveWeight() {
        return active ? weight : BigDecimal.ZERO;
    }
}
