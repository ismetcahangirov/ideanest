package az.ideanest.discovery.domain;

/**
 * A thing a query can ask for that an implementation may or may not be able to do.
 *
 * <p><strong>This is how "not built yet" is said in the type system rather than in
 * a comment.</strong> Every option §4.3 lists is representable on
 * {@code DiscoveryQuery} today, because the alternative is that #43, #44, #47 and
 * #48 each widen the query object and each conflict with the others in the same
 * file. What separates a representable option from a working one is
 * {@code SearchService.capabilities()}: the query says what it needs, the
 * implementation says what it has, and the difference becomes an RFC 9457 problem
 * detail naming the issue that owns it.
 *
 * <p>The alternative — accepting the parameter and ignoring it — is worse than
 * refusing it. A backer who typed a search term and was silently shown every
 * campaign on the platform has been told the search works, and the bug report that
 * eventually arrives is about relevance rather than about the feature being absent.
 *
 * <p>When one of these lands, its owner adds the constant to
 * {@code PostgresSearchService.capabilities()} and implements it. Nothing else
 * changes, and the tests that pin today's refusals are the tests that will fail if
 * a capability is announced without being built.
 */
public enum DiscoveryCapability {

    /**
     * Free-text matching over title, blurb, story, and creator (D-01). <strong>#43,
     * and the first of these to land.</strong>
     *
     * <p>Needs {@code projects.search_vector} — V13 — and therefore also the fold of
     * §11.3, the trigram tier for D-03, and the triggers that keep the vector true.
     * An implementation that declares this must serve {@code q} on
     * {@code /v1/discover}, the whole of {@code /v1/search}, and
     * {@link DiscoverySort#BEST_MATCH}; the three are one feature and a client that
     * got two of them would find that its search results could not be sorted.
     */
    FULL_TEXT("q", "#43 (full-text search)"),

    /**
     * The composite ranking of §11.2. <strong>#44, and served.</strong>
     *
     * <p>Needs {@code ranking_weights} — V15 — because §11.2 requires the weights to be
     * tunable without a deployment, so an implementation that hard-codes them does not
     * satisfy this capability however well it ranks. It must also serve
     * {@link DiscoverySort#RELEVANCE} on {@code /v1/discover} and on {@code /v1/search}
     * and compose it with the keyset cursor; the three are one feature, and a client
     * that got two of them would find its search results could not be paged.
     *
     * <p>It does <strong>not</strong> imply {@link #FILTER_RECOMMENDED}. The composite
     * ranks campaigns and does not know who is reading; see that constant.
     */
    SORT_RELEVANCE("sort=relevance", "#44 (ranking)"),

    /**
     * Great-circle distance from an origin the caller supplies. <strong>#47, and
     * served.</strong>
     *
     * <p>Needs V16 — {@code locations}, {@code projects.location_id}, and the
     * {@code cube}/{@code earthdistance} arithmetic the distance is measured with. An
     * implementation that declares this must serve {@link DiscoverySort#NEAR_ME} on
     * {@code /v1/discover} and on {@code /v1/search} and compose it with the keyset
     * cursor, pinning the origin the way this one pins it; the three are one feature,
     * and a client that got two of them would find its nearest-first feed could not be
     * paged.
     *
     * <p>It does <strong>not</strong> imply {@link #FILTER_PROXIMITY}. Ordering by
     * distance and bounding by a radius are two controls (§4.3 lists the sort in one
     * table and the filter in the other), and an implementation could sort without
     * being able to filter.
     */
    SORT_NEAR_ME("sort=near_me", "#47 (proximity)"),

    /**
     * Filtering by country and city. <strong>#47, and served.</strong>
     *
     * <p>Was not merely unimplemented — until V16 there was nothing to filter on:
     * {@code projects.location_id} was listed in §7.2 and deliberately absent from the
     * schema, and no {@code locations} table existed. The filter was representable so
     * that #47 had a shape to fill in, which is what this whole enum is for.
     *
     * <p>An implementation that declares this must serve {@code country} and
     * {@code city} on both endpoints <em>and</em> count them in the facet panel, as one
     * dimension rather than two; the three are one feature, and a client that got two
     * of them would find that ticking a city changed the feed and not the numbers
     * beside it.
     */
    FILTER_LOCATION("country / city", "#47 (proximity), which brings the location schema"),

    /**
     * A bounded radius around an origin. <strong>#47, and served.</strong>
     *
     * <p>The parameter is {@code near=lat,lon} with {@code radiusKm}; the capability is
     * named for {@code near} because that is the parameter that cannot be honoured
     * without the schema. The bound is not optional — see
     * {@code LocationFilter.Proximity.MAX_RADIUS_KILOMETRES} — because an unbounded
     * radius is "everywhere" with arithmetic attached.
     */
    FILTER_PROXIMITY("near", "#47 (proximity)"),

    /**
     * Only campaigns this caller saved.
     *
     * <p>Requires an authenticated caller and a saved-projects table. §7.2 mentions
     * {@code saves} as a backer signal and no migration has created it, so the
     * filter cannot be honoured by any implementation yet.
     */
    FILTER_SAVED("showOnly=saved", "the saved-projects table, which no migration has created"),

    /**
     * Only campaigns recommended to this caller.
     *
     * <p><strong>Still refused after #44, and this is the interesting case.</strong> The
     * composite ranking landed and this did not, because they are not the same feature:
     * §11.2's {@code w6} is personalisation and it is one of the five terms seeded inert
     * in V15, for a reason that is not effort. {@code GET /v1/discover} is
     * unauthenticated and publicly cached for a minute — the same bytes for everybody is
     * what makes §20's thousand requests a second reachable — so there is no caller to
     * personalise for and nowhere to put a per-caller signal.
     *
     * <p>Answering it out of the composite anyway would tell every reader that a feed
     * computed identically for all of them had been assembled for them personally, which
     * is a stronger claim than the platform can make. Answering it with the staff picks
     * would be worse still: it would present an editorial decision as a personal one.
     */
    FILTER_RECOMMENDED(
            "showOnly=recommended",
            "D-07 (personalised feed): §11.2's w6 is inert, and this endpoint has no caller to rank for"),

    /**
     * Only editorially featured campaigns. <strong>#48, and served.</strong>
     *
     * <p>Needs V14's {@code collections} and {@code collection_projects}, and the
     * {@code project_editorial_badges} view that derives the badge from them. An
     * implementation that declares this must serve {@code showOnly=featured} on
     * {@code /v1/discover} and on {@code /v1/search}, and must count it in the facet
     * panel — the three are one feature, and a client that got two of them would find
     * that ticking the filter changed the feed and not the numbers beside it.
     */
    FILTER_FEATURED("showOnly=featured", "#48 (collections and curation)"),

    /**
     * §4.3's Programmes: narrowing the feed to a themed open call.
     * <strong>#48, and served.</strong>
     *
     * <p>Declared rather than left implicit even though PostgreSQL can serve it
     * today, for the reason on {@link #FILTER_LOCATION}: this filter is a join to a
     * table an external index does not have, and a tier-2 implementation that cannot
     * do the join has to refuse it loudly rather than return a feed that ignores it.
     */
    FILTER_PROGRAMME("programme", "#48 (collections and curation)");

    private final String parameter;
    private final String owner;

    DiscoveryCapability(String parameter, String owner) {
        this.parameter = parameter;
        this.owner = owner;
    }

    /** The query parameter, spelled as a client would send it, for the problem detail. */
    public String parameter() {
        return parameter;
    }

    /**
     * What has to exist before this works, named so a client's developer can find it.
     *
     * <p>An error that says "not supported" and stops sends somebody to read the
     * source. One that names the issue sends them to the issue.
     */
    public String owner() {
        return owner;
    }
}
