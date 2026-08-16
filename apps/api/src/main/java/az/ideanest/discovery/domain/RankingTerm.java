package az.ideanest.discovery.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The terms of §11.2's composite, and which of them anything can actually compute.
 *
 * <p>§11.2 writes the formula as eight weighted terms. <strong>Three of the eight are
 * live and five have no data source in this schema</strong>, and this enum is where
 * that is said in the type system rather than in a comment — the same job
 * {@link DiscoveryCapability} does for the query options, and for the same reason. A
 * ranking formula that quietly drops five of its eight terms is precisely the thing
 * "ranking can be measured rather than argued about" exists to prevent: the feed looks
 * ranked, the weights look tuned, and nobody can tell which half of the specification
 * is running.
 *
 * <p><strong>An inert term is not absent.</strong> Its row exists in
 * {@code ranking_weights}, its weight is settable, the moderator diagnostic reports it
 * with its reason, and {@link #blockedBy()} names what has to land first. What it
 * cannot be is {@code active} — V15's {@code ranking_weights_inert_terms_are_not_active}
 * refuses that outright — because switching on a term nothing computes is a change that
 * looks like it did something and did not.
 *
 * <p><strong>{@link #TEXT_MATCH} is a ninth term and is not in §11.2's list.</strong>
 * §4.3 settled it when #43 landed: {@code best_match} "becomes its text term rather than
 * being replaced by it". Without it, {@code sort=relevance&q=robot} would rank the
 * campaign named "Robot" exactly as it ranks one that has never used the word.
 * {@code docs/architecture.md} §11.2 records the addition.
 */
public enum RankingTerm {

    /**
     * How well the campaign's text matches what the reader typed. <strong>Live.</strong>
     *
     * <p>#43's {@code ts_rank} over the folded search vector — the identical expression
     * {@link DiscoverySort#BEST_MATCH} orders by, clamped into {@code [0, 1]} — so the
     * two orders cannot come to disagree about what a good text match is.
     *
     * <p>Zero for every campaign when there is no query, which is what lets one sort
     * serve a searched feed and a browsing one: on a browsing feed the term contributes
     * nothing to anybody and the other live terms decide the order among themselves.
     */
    TEXT_MATCH("text_match", Sign.ADDS, null),

    /**
     * §11.2's {@code w1}: money raised in the last 48 hours. <strong>Inert.</strong>
     *
     * <p>{@code projects.pledged_amount} is a running total and there is no pledge
     * ledger behind it (epic #50), so there is nothing to difference against. The
     * available approximation — money over a power of the campaign's age — is
     * {@link DiscoverySort#POPULARITY}, which exists, is named for what it is, and says
     * in its own comment that it is not this. Serving it here under the word "velocity"
     * would be the same number wearing a better name.
     */
    PLEDGE_VELOCITY("pledge_velocity", Sign.ADDS, "#50 (pledge ledger)"),

    /** §11.2's {@code w2}: backers gained in 48 hours. <strong>Inert</strong>, as {@code w1} is. */
    BACKER_VELOCITY("backer_velocity", Sign.ADDS, "#50 (pledge ledger)"),

    /** §11.2's {@code w3}: how close the campaign is to its goal. <strong>Live.</strong> */
    COMPLETION("completion", Sign.ADDS, null),

    /**
     * §11.2's {@code w4}: the editorial badge. <strong>Live</strong>, from #48's view.
     *
     * <p>Read through {@code project_editorial_badges} rather than through a column,
     * because that view is the only definition of "editorially featured" on the
     * platform — the discovery filter, the card, the project header and this term are
     * four readers of one rule, and V14 made them so on purpose.
     */
    EDITORIAL("editorial", Sign.ADDS, null),

    /**
     * §11.2's {@code w5}: view-to-pledge conversion. <strong>Inert.</strong>
     *
     * <p>Neither half of the ratio exists. Nothing records a view anywhere in the
     * platform, and the aggregation that would is #95.
     */
    CONVERSION("conversion", Sign.ADDS, "#95 (analytics aggregation)"),

    /**
     * §11.2's {@code w6}: personalisation. <strong>Inert.</strong>
     *
     * <p>D-07's feed needs to know who is asking, and {@code GET /v1/discover} is
     * unauthenticated and publicly cached for a minute — the same response for
     * everybody is what makes §20's thousand requests a second reachable at all. Turning
     * this on is therefore not one term but a different endpoint, and it is what keeps
     * {@link DiscoveryCapability#FILTER_RECOMMENDED} refused.
     */
    PERSONALISATION("personalisation", Sign.ADDS, "D-07 (personalised feed) and per-caller signals"),

    /** §11.2's {@code w7}: recency decay. <strong>Live</strong>, from {@code launched_at}. */
    RECENCY("recency", Sign.ADDS, null),

    /**
     * §11.2's {@code w8}: the spam signal, <strong>subtracted</strong>. <strong>Inert.</strong>
     *
     * <p>The sign lives here rather than in the stored weight, so the column can be
     * constrained non-negative: a weight that accepted negatives would let somebody
     * invert any term by typing one character.
     */
    SPAM("spam", Sign.SUBTRACTS, "#108 (automated fraud signals)");

    /** Whether a term is added to the composite or taken off it. See {@link #SPAM}. */
    public enum Sign {
        ADDS,
        SUBTRACTS
    }

    private static final Map<String, RankingTerm> BY_WIRE_VALUE = byWireValue();

    private final String wireValue;
    private final Sign sign;
    private final String blockedBy;

    RankingTerm(String wireValue, Sign sign, String blockedBy) {
        this.wireValue = wireValue;
        this.sign = sign;
        this.blockedBy = blockedBy;
    }

    /** The spelling in {@code ranking_weights.term}, in the admin API, and in the diagnostic. */
    public String wireValue() {
        return wireValue;
    }

    public Sign sign() {
        return sign;
    }

    /**
     * What has to exist before this term can be computed, or null when it can be today.
     *
     * <p>Named rather than left as a boolean for the reason
     * {@link DiscoveryCapability#owner()} is: "not implemented" sends somebody to read
     * the source, and an issue number sends them to the issue.
     */
    public String blockedBy() {
        return blockedBy;
    }

    /** Whether this term has a data source in the schema today. */
    public boolean isComputable() {
        return blockedBy == null;
    }

    public static Optional<RankingTerm> fromWireValue(String value) {
        return Optional.ofNullable(value).map(BY_WIRE_VALUE::get);
    }

    /** Every term, in §11.2's order, for an error message and for the diagnostic. */
    public static List<String> wireValues() {
        return BY_WIRE_VALUE.keySet().stream().toList();
    }

    private static Map<String, RankingTerm> byWireValue() {
        Map<String, RankingTerm> map = new LinkedHashMap<>();
        for (RankingTerm term : values()) {
            map.put(term.wireValue, term);
        }
        return Collections.unmodifiableMap(map);
    }
}
