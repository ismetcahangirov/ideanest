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

    /**
     * How well the campaign's text matches the words that were typed (D-01).
     * <strong>The default when {@code q} is present and no sort is named.</strong>
     *
     * <p><strong>This is not {@link #RELEVANCE}, and the difference is the whole
     * reason it exists.</strong> §11.2's relevance is a composite of pledge
     * velocity, backer velocity, completion, curation, personalisation and decay —
     * seven terms, none of which is about what the reader typed — and it is #44's
     * to build. Text relevance is {@code ts_rank} over the folded search vector: a
     * title match outranks a blurb match outranks a story match, and that is all it
     * claims. Serving one under the other's name would mean that when #44 lands,
     * either {@code sort=relevance} changes meaning underneath every client or the
     * composite has to be given a different word.
     *
     * <p><strong>Why a sort of its own rather than "the order used when q is
     * present".</strong> They are the same thing here — {@code DiscoveryQuery}
     * resolves an unstated sort to this one exactly when there is text to rank by —
     * but the cursor carries the sort it was issued for and the fingerprint hashes
     * it, so the order a page was produced in has to have a name. It is also what
     * lets a client that filtered its way to text search and then chose
     * {@code sort=newest} switch back.
     *
     * <p><strong>What #44 layers on top.</strong> {@code RELEVANCE} becomes a
     * second scoring expression beside this one, not a replacement for it: the
     * §11.2 composite has a text term, and this is the text term. Nothing about
     * this constant, its cursor encoding, or its index has to change for that.
     *
     * <p>Requires {@link DiscoveryCapability#FULL_TEXT}, because a text score
     * needs a search vector. A query that names it without a {@code q} has nothing
     * to rank and is resolved back to {@link #DEFAULT} — see {@code DiscoveryQuery}.
     */
    BEST_MATCH("best_match"),

    /** §11.2's composite. <strong>#44</strong>; refused until then. */
    RELEVANCE("relevance"),

    /** Geographic distance. <strong>#47</strong>; refused until then. */
    NEAR_ME("near_me"),

    /**
     * The curator's own sequence. <strong>Not selectable by a client.</strong>
     *
     * <p>D-08's landing page is an edited list — {@code collection_projects.position}
     * ascending, {@code project_id} as the tiebreaker that makes it total — and the
     * first card on a staff-picks page is a choice somebody made rather than whatever
     * launched most recently.
     *
     * <p><strong>Why it is in this enum at all.</strong> A cursor carries the order it
     * was issued for and refuses to be replayed against another one
     * ({@code DiscoveryCursor}), so every order this module produces has to have a
     * name. Giving the collection page a cursor type of its own instead would mean a
     * second encoding, a second version field, and a second set of the mistakes
     * {@code DiscoveryCursor}'s comments are about.
     *
     * <p><strong>Why it is not selectable.</strong> {@code ?sort=curated} on
     * {@code /v1/discover} would name an order that does not exist there: outside a
     * collection there is no curator and no position, so the honest answers are a
     * refusal or a silent fallback, and a silent fallback is the failure
     * {@link DiscoveryCapability} exists to prevent. {@link #isClientSelectable} is
     * how the binder says so, and it is left out of {@link #wireValues()} so it never
     * appears in the list of values a client is told it may send.
     */
    CURATED("curated");

    /** What a client sends when it sends nothing and is browsing. See {@link #NEWEST}. */
    public static final DiscoverySort DEFAULT = NEWEST;

    /**
     * What a client sends when it sends nothing and is searching.
     *
     * <p>A feed with no query has no relevance to order by; a feed with one has
     * nothing else worth opening on. Sorting search results by launch date puts the
     * campaign that mentions the word once in its ninth paragraph above the one
     * named after it, and every reader reads that as "the search does not work".
     */
    public static final DiscoverySort DEFAULT_WITH_TEXT = BEST_MATCH;

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
            case BEST_MATCH -> Optional.of(DiscoveryCapability.FULL_TEXT);
            case RELEVANCE -> Optional.of(DiscoveryCapability.SORT_RELEVANCE);
            case NEAR_ME -> Optional.of(DiscoveryCapability.SORT_NEAR_ME);
            case NEWEST, ENDING_SOON, MOST_FUNDED, MOST_BACKED, POPULARITY, CURATED -> Optional.empty();
        };
    }

    /**
     * Whether a client may ask for this order by name.
     *
     * <p>False only for {@link #CURATED}, which exists because a cursor names its
     * order and not because anybody may choose it. See that constant.
     */
    public boolean isClientSelectable() {
        return this != CURATED;
    }

    /**
     * The order named, whether or not a client may select it.
     *
     * <p>Deliberately does not filter: {@code DiscoveryCursor.decode} calls this to
     * read back an order this service issued, and a cursor for the collection page
     * has to decode. The binder is where selectability is enforced, because that is
     * where a client's input arrives.
     */
    public static Optional<DiscoverySort> fromWireValue(String value) {
        return Optional.ofNullable(value).map(BY_WIRE_VALUE::get);
    }

    /** Every order a client may ask for, in declaration order, for an error message. */
    public static List<String> wireValues() {
        return BY_WIRE_VALUE.values().stream()
                .filter(DiscoverySort::isClientSelectable)
                .map(DiscoverySort::wireValue)
                .toList();
    }

    private static Map<String, DiscoverySort> byWireValue() {
        Map<String, DiscoverySort> map = new LinkedHashMap<>();
        for (DiscoverySort sort : values()) {
            map.put(sort.wireValue, sort);
        }
        return Collections.unmodifiableMap(map);
    }
}
