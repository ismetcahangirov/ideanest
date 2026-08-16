package az.ideanest.discovery.api;

import az.ideanest.discovery.application.RankingRejectedException;
import az.ideanest.discovery.application.RankingService;
import az.ideanest.discovery.domain.RankingTerm;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tuning the ranking, and seeing what it did. §11.2's "measured rather than argued
 * about", as two endpoints.
 *
 * <h2>Why this exists</h2>
 *
 * <p>§11.2 requires the weights to be tunable without a deployment. A table alone does
 * not satisfy that: if the only way to change a weight is {@code psql} against
 * production, the change is unaudited, unreviewable, and available to whoever has the
 * credentials rather than to whoever holds the role. So the table has a door, and the
 * door is where the moderator check and the audit row are.
 *
 * <p><strong>And a diagnostic, which is the half that makes tuning possible at all.</strong>
 * A feed answers with an order. An order is a comparison of totals, and a total is one
 * number in which four terms have already been added together — so the question anybody
 * tuning actually has ("is this campaign above that one because of its badge or because
 * it launched this morning?") is unanswerable from the feed. {@code GET
 * /v1/admin/ranking/explain/{slug}} answers it: every term, its normalised value, its
 * weight, and the product, computed by the same expressions the feed orders by.
 *
 * <h2>Who may</h2>
 *
 * <p>Platform staff, through the same configured directory the moderation and curation
 * endpoints use. Changing a weight moves every campaign in every feed at once, which is
 * a larger act than any single curation decision; and the diagnostic is a specification
 * of how to rank highly on this platform, which is not something to hand to whoever asks
 * for it. See {@link RankingService}.
 *
 * <h2>What is deliberately absent</h2>
 *
 * <ul>
 *   <li><strong>No endpoint over {@code ranking_weight_changes}.</strong> AD-14's
 *       platform-wide immutable log belongs to epic #100, and a ranking-only view of it
 *       now would be a surface to replace rather than extend — the same judgement
 *       {@link AdminCurationController} made about {@code curation_events}. The rows are
 *       written and indexed for the two questions that will be asked of them; see V15.
 *   <li><strong>No way to add, remove, or describe a term.</strong> The vocabulary is
 *       §11.2's and a CHECK constraint's, and {@code blocked_by} is a fact about the
 *       code rather than about anybody's tuning. Clearing it from a screen would
 *       announce that a term is computed when nothing computes it.
 *   <li><strong>No bulk set.</strong> Nine weights in one request would produce one
 *       audit row for nine decisions or nine rows with one note; both make the trail
 *       worse, and a curator setting four weights makes four decisions.
 * </ul>
 */
@RestController
@RequestMapping("/v1/admin/ranking")
public class AdminRankingController {

    private final RankingService ranking;

    public AdminRankingController(RankingService ranking) {
        this.ranking = ranking;
    }

    /**
     * Every term: its weight, whether it counts, and what is blocking it if it does not.
     *
     * <p>The five inert terms are in this list, which is the point of the list. A
     * response holding only what runs would let somebody tune four weights believing
     * they were tuning §11.2.
     */
    @GetMapping("/weights")
    public RankingResponses.WeightsResponse weights(@AuthenticationPrincipal Jwt accessToken) {
        return RankingResponses.weights(ranking.list(curatorOf(accessToken)));
    }

    /**
     * Sets one term's weight and whether it is in the sum.
     *
     * <p>{@code PUT}, because it states the whole of a term's setting and is idempotent:
     * sending it twice leaves the same row and — because a change that changes nothing
     * writes no audit row — leaves one entry in the trail rather than two.
     *
     * <p>The note is required. §11.2 asks for ranking to be measurable, and a weight
     * moved with no stated hypothesis is unmeasurable by construction.
     */
    @PutMapping("/weights/{term}")
    public RankingResponses.WeightsResponse setWeight(
            @AuthenticationPrincipal Jwt accessToken,
            @PathVariable String term,
            @Valid @RequestBody RankingRequests.SetWeight request) {

        return RankingResponses.weights(ranking.update(
                termOf(term), request.weight(), request.active(), request.note(), curatorOf(accessToken)));
    }

    /**
     * Why one campaign scores what it scores.
     *
     * @param q the query to score the text term against, optional. Without it the text
     *     term is zero for every campaign, which is exactly what a browsing feed does —
     *     so an explanation taken without {@code q} explains the browsing feed and one
     *     taken with it explains {@code /v1/search?sort=relevance}
     */
    @GetMapping("/explain/{slug}")
    public RankingResponses.ExplanationResponse explain(
            @AuthenticationPrincipal Jwt accessToken,
            @PathVariable String slug,
            @RequestParam(required = false) String q) {

        return RankingResponses.explanation(
                ranking.explain(slug, q == null || q.isBlank() ? null : q.trim(), curatorOf(accessToken)));
    }

    /**
     * The term named, or a refusal listing the nine.
     *
     * <p>A 400 with the vocabulary in it rather than a 404, for the reason
     * {@code UnknownFilterValueException} exists: the path segment is a value from a
     * closed set, and the useful answer to a misspelt one is the set.
     */
    private static RankingTerm termOf(String term) {
        return RankingTerm.fromWireValue(term)
                .orElseThrow(() -> new RankingRejectedException(
                        "term", "'" + term + "' is not a ranking term. §11.2 has " + RankingTerm.wireValues() + "."));
    }

    /**
     * Whoever is signed in.
     *
     * <p>Checked against the configured moderator list by {@link RankingService} before
     * anything is read — not only before anything is written. The weights are a
     * description of how to rank highly, and a campaign that knew them would know what
     * to optimise.
     */
    private static UUID curatorOf(Jwt accessToken) {
        return UUID.fromString(accessToken.getSubject());
    }
}
