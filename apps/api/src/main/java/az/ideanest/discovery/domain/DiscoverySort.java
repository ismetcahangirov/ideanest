package az.ideanest.discovery.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The seven orders §4.3 offers, and what each one costs to serve.
 *
 * <p>All seven are declared now. Two of them belong to other issues and are refused
 * by {@code PostgresSearchService} through {@link DiscoveryCapability}; declaring
 * them anyway is what stops #44 and #47 from each having to widen this enum, and
 * what lets a client discover the vocabulary before the features land.
 *
 * <p><strong>Every order is total.</strong> Each carries a tiebreaker of
 * {@code id ASC} on top of its sort key, and nothing else would be correct: a
 * keyset cursor over a non-total order duplicates and drops rows at every page
 * boundary where two campaigns share a key, and on this platform thousands of
 * campaigns share a {@code pledged_amount} of zero.
 */
public enum DiscoverySort {

    /**
     * {@code launched_at DESC}. <strong>The default.</strong>
     *
     * <p>Chosen as the default because it is the only order that is meaningful with
     * no filter applied. {@link #ENDING_SOON} sorts a campaign whose deadline passed
     * in 2024 above one ending tomorrow, so an unfiltered feed ordered by it opens
     * on the oldest finished campaigns on the platform; {@link #MOST_FUNDED} and
     * {@link #MOST_BACKED} open on the same handful of campaigns for everybody, for
     * ever. Newest changes, is comprehensible, and cannot be gamed by the size of
     * somebody's launch.
     *
     * <p>Nulls last: a campaign in {@code PRELAUNCH} has never launched, and an
     * unlaunched teaser is not the newest thing on the platform.
     *
     * <p><em>V6 calls ending-soon "the default discovery sort" in a comment beside
     * {@code projects_state_deadline_idx}. That aside is superseded here, for the
     * reason above. The index it describes is still exactly the right index for
     * {@link #ENDING_SOON}.</em>
     */
    NEWEST("newest"),

    /**
     * {@code deadline ASC}. Nulls last, for the campaigns that have no deadline yet.
     *
     * <p>Almost always sent with {@code status=live}; without it the order is
     * dominated by campaigns that ended long ago. That is the client's decision and
     * not this endpoint's — silently adding a status filter to a sort would make one
     * parameter change the meaning of another.
     */
    ENDING_SOON("ending_soon"),

    /** {@code pledged_amount DESC}. Never null; the column defaults to zero. */
    MOST_FUNDED("most_funded"),

    /** {@code backers_count DESC}. Never null; the column defaults to zero. */
    MOST_BACKED("most_backed"),

    /**
     * Pledge velocity with time decay (§4.3).
     *
     * <p>The score is money raised divided by a power of the campaign's age, which
     * is the gravity form used everywhere for this shape of ranking. What it is
     * <em>not</em> is §11.2's relevance: that composite needs pledge and backer
     * velocity over a 48-hour window, and there is no pledge ledger yet (epic #50) —
     * {@code projects.pledged_amount} is a running total with no history behind it.
     * So the decay approximates velocity from what exists: a campaign that raised
     * 10,000 in two days outranks one that raised 12,000 in fifty.
     *
     * <p><strong>The clock is pinned by the cursor.</strong> A score computed against
     * {@code now()} moves between page one and page two, which is exactly the
     * duplicate-and-drop failure a keyset cursor exists to prevent. The first page
     * chooses an instant, the cursor carries it, and every later page recomputes the
     * same scores. See {@code DiscoveryCursor}.
     *
     * <p><strong>This is the one order no index serves.</strong> The score is a
     * function of two columns and a parameter, so PostgreSQL sorts the matching rows.
     * At §11.1's tier-1 ceiling of roughly ten thousand campaigns that is a sort of a
     * few thousand rows and costs under a millisecond; past it, this is one of the
     * things that moves to the engine.
     */
    POPULARITY("popularity"),

    /** §11.2's composite. <strong>#44</strong>; refused until then. */
    RELEVANCE("relevance"),

    /** Geographic distance. <strong>#47</strong>; refused until then. */
    NEAR_ME("near_me");

    /** What a client sends when it sends nothing. See {@link #NEWEST}. */
    public static final DiscoverySort DEFAULT = NEWEST;

    private static final Map<String, DiscoverySort> BY_WIRE_VALUE = byWireValue();

    private final String wireValue;

    DiscoverySort(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    /** The capability this order needs, or empty when PostgreSQL can serve it today. */
    public Optional<DiscoveryCapability> requiredCapability() {
        return switch (this) {
            case RELEVANCE -> Optional.of(DiscoveryCapability.SORT_RELEVANCE);
            case NEAR_ME -> Optional.of(DiscoveryCapability.SORT_NEAR_ME);
            case NEWEST, ENDING_SOON, MOST_FUNDED, MOST_BACKED, POPULARITY -> Optional.empty();
        };
    }

    public static Optional<DiscoverySort> fromWireValue(String value) {
        return Optional.ofNullable(value).map(BY_WIRE_VALUE::get);
    }

    public static List<String> wireValues() {
        return List.copyOf(BY_WIRE_VALUE.keySet());
    }

    private static Map<String, DiscoverySort> byWireValue() {
        Map<String, DiscoverySort> map = new LinkedHashMap<>();
        for (DiscoverySort sort : values()) {
            map.put(sort.wireValue, sort);
        }
        return Collections.unmodifiableMap(map);
    }
}
