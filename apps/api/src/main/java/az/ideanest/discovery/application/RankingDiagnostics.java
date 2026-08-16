package az.ideanest.discovery.application;

import java.util.Optional;

/**
 * "Why is this campaign here?", answered by whatever is ranking.
 *
 * <p>A second interface on the §11.1 seam, and separate from {@link SearchService} on
 * purpose. A tier-2 implementation must serve a feed; it is not obliged to be able to
 * take one campaign apart, because an external engine may not expose per-term scores at
 * all. Bundling this into {@code SearchService} would make an engine that ranks
 * perfectly well fail to satisfy the interface over a moderator tool.
 *
 * <p>What an implementation that <em>does</em> offer it owes: the numbers must come from
 * the same expressions the feed orders by. An explanation computed by a second code path
 * is a plausible story about a position rather than the reason for it, and the day the
 * two drift is the day somebody spends an afternoon tuning against a fiction.
 */
public interface RankingDiagnostics {

    /**
     * One campaign's composite, taken apart.
     *
     * @param slug the campaign, which must be publicly visible — the diagnostic is a
     *     moderator tool and is still not a way to read a draft, for the reason
     *     {@code ProjectNotFoundException} gives
     * @param text the query to score the text term against, or null for none. Present so
     *     that {@code sort=relevance&q=…} can be explained as it is actually served
     * @param weights the snapshot to score against, so that a caller comparing two
     *     campaigns can be certain both were scored with the same configuration
     * @return empty when no publicly visible campaign has that slug
     */
    Optional<RankingExplanation> explain(String slug, String text, RankingWeights weights);
}
