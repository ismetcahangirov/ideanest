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
     * Free-text matching over title, blurb, story, and creator (D-01). <strong>#43.</strong>
     *
     * <p>{@code projects.search_vector} does not exist yet; V6 says so and says why.
     */
    FULL_TEXT("q", "#43 (full-text search)"),

    /** The composite ranking of §11.2 (D-07). <strong>#44.</strong> */
    SORT_RELEVANCE("sort=relevance", "#44 (ranking)"),

    /** Geographic distance from the caller. <strong>#47.</strong> */
    SORT_NEAR_ME("sort=near_me", "#47 (proximity)"),

    /**
     * Filtering by country and city.
     *
     * <p>Not merely unimplemented — <strong>there is nothing to filter on</strong>.
     * {@code projects.location_id} is listed in §7.2 and is deliberately absent from
     * the schema, and no {@code locations} table exists. The filter is representable
     * so that #47 has a shape to fill in; until the column exists, asking for it is
     * refused rather than answered with everything.
     */
    FILTER_LOCATION("country / city", "#47 (proximity), which brings the location schema"),

    /** A radius around a point. <strong>#47.</strong> */
    FILTER_PROXIMITY("near", "#47 (proximity)"),

    /**
     * Only campaigns this caller saved.
     *
     * <p>Requires an authenticated caller and a saved-projects table. §7.2 mentions
     * {@code saves} as a backer signal and no migration has created it, so the
     * filter cannot be honoured by any implementation yet.
     */
    FILTER_SAVED("showOnly=saved", "the saved-projects table, which no migration has created"),

    /** Only campaigns recommended to this caller. <strong>#44.</strong> */
    FILTER_RECOMMENDED("showOnly=recommended", "#44 (ranking)"),

    /** Only editorially featured campaigns. <strong>#48.</strong> */
    FILTER_FEATURED("showOnly=featured", "#48 (collections and curation)");

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
