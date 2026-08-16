package az.ideanest.discovery.domain;

/**
 * One line of {@code GET /v1/search/suggest}. D-02.
 *
 * <p><strong>A suggestion says what kind of thing it is, and that is the point of
 * the type.</strong> A list of bare strings would make the client guess: "games"
 * is a category, "handmade" is a tag, and "Games Night" is a campaign, and each
 * of the three leads somewhere different — {@code ?category=games},
 * {@code ?tag=handmade}, and a project page. A suggestion list that cannot be
 * acted on differently per row is a list that can only be pasted back into the
 * search box, which is the one thing the reader could already do.
 *
 * <p>{@link #kind} therefore carries the routing decision and {@link #slug}
 * carries the value the client puts in the URL, so #46 (the web autocomplete)
 * renders and navigates without a second request and without a lookup table of
 * its own.
 *
 * @param kind what this suggestion is
 * @param label what to show. The <em>unfolded</em> form — a campaign's title as
 *     its creator wrote it, a category's name in the negotiated language, a tag's
 *     label as the first creator to use it spelled it. Never the folded form:
 *     showing a reader "secenek" when they typed "seç" spells their own language
 *     wrong back at them
 * @param slug what to send back. The campaign's slug, the category's or
 *     subcategory's slug, or the tag's folded slug
 * @param parentSlug the category a subcategory belongs to, and null for every
 *     other kind. Subcategory slugs are unique within a parent and not globally
 *     (V6), so the pair is what identifies one
 */
public record Suggestion(Kind kind, String label, String slug, String parentSlug) {

    /**
     * The kinds of thing a search box can suggest.
     *
     * <p>Three sources, and no more: campaign titles, the taxonomy, and the tag
     * vocabulary. Creators are deliberately absent even though D-01 makes them
     * searchable — a suggestion list that offers people by name is a directory of
     * the platform's users, and §17.4 is careful about which of those are
     * enumerable. Somebody searching for a creator finds their campaigns.
     */
    public enum Kind {

        /** A campaign, by its title. Publicly visible states only. */
        CAMPAIGN("campaign"),

        /** One of §4.3's fifteen primary categories. */
        CATEGORY("category"),

        /** A subcategory; {@link Suggestion#parentSlug} names its category. */
        SUBCATEGORY("subcategory"),

        /** A tag from the free vocabulary. */
        TAG("tag");

        private final String wireValue;

        Kind(String wireValue) {
            this.wireValue = wireValue;
        }

        public String wireValue() {
            return wireValue;
        }
    }
}
