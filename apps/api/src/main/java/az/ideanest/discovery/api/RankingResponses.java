package az.ideanest.discovery.api;

import az.ideanest.discovery.application.RankingExplanation;
import az.ideanest.discovery.application.RankingWeight;
import az.ideanest.discovery.application.RankingWeights;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * What {@link AdminRankingController} answers with.
 *
 * <p>Records rather than the application types, for the reason
 * {@code DiscoveryResponses} gives: the wire shape is a contract with a client and the
 * application types are free to change. It also lets the two things a reader most needs
 * be named for what they are — {@code blockedBy} and {@code contribution} — rather than
 * inferred from a zero.
 */
final class RankingResponses {

    private RankingResponses() {
    }

    static WeightsResponse weights(RankingWeights weights) {
        return new WeightsResponse(
                weights.version(),
                weights.weights().stream().map(RankingResponses::weight).toList());
    }

    private static WeightResponse weight(RankingWeight weight) {
        return new WeightResponse(
                weight.term().wireValue(),
                // Numbers rather than strings: see RankingRequests.SetWeight. A weight
                // is not money.
                weight.weight(),
                weight.active(),
                weight.blockedBy(),
                weight.description(),
                weight.updatedAt());
    }

    static ExplanationResponse explanation(RankingExplanation explanation) {
        return new ExplanationResponse(
                explanation.slug(),
                explanation.title(),
                explanation.weightsVersion(),
                explanation.total(),
                explanation.terms().stream()
                        .map(term -> new TermResponse(
                                term.term().wireValue(),
                                term.active(),
                                term.blockedBy(),
                                term.value(),
                                term.weight(),
                                term.contribution()))
                        .toList());
    }

    /**
     * @param version the digest a cursor is bound to; two feeds taken either side of a
     *     change can be told apart by it
     */
    record WeightsResponse(String version, List<WeightResponse> terms) {
    }

    /**
     * @param blockedBy omitted when the term has data today, present and naming the
     *     issue when it does not
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record WeightResponse(
            String term,
            BigDecimal weight,
            boolean active,
            String blockedBy,
            String description,
            Instant updatedAt) {
    }

    /**
     * @param total the composite as the feed's {@code ORDER BY} computes it, rounded to
     *     six places. The per-term contributions are exact products and therefore sum to
     *     this within that rounding
     */
    record ExplanationResponse(
            String slug, String title, String weightsVersion, BigDecimal total, List<TermResponse> terms) {
    }

    /**
     * @param value <strong>omitted, not zero, for a term nothing computes.</strong> Zero
     *     is a campaign that scores nothing on a term that works; absence is a term that
     *     does not work, and the two are the difference between "this campaign has no
     *     momentum" and "this platform does not measure momentum"
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record TermResponse(
            String term,
            boolean active,
            String blockedBy,
            BigDecimal value,
            BigDecimal weight,
            BigDecimal contribution) {
    }
}
