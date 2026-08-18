package az.ideanest.analytics.domain;

/**
 * What kind of place a visit came from. §4.7's CD-03 groups by it.
 *
 * <p><strong>A closed set, and small on purpose.</strong> The free text a link can
 * carry is {@code source} and {@code campaign}; this is the axis a creator reads
 * first — "how much of this came from social at all" — and an axis whose values are
 * whatever anybody typed cannot answer that question. A campaign that fills the
 * report with fourteen spellings of "instagram" still fills exactly one channel.
 *
 * <p>Mirrored by {@code referral_touches_channel_known} and
 * {@code referral_attributions_channel_known} rather than stored as a PostgreSQL enum
 * type, for the reason V19 gives about {@code event_type}: adding a value should be a
 * migration, not a deployment-ordering problem between a type and the code that uses
 * it.
 */
public enum ReferralChannel {

    /**
     * Nobody sent them. A typed address, a bookmark, an application with no referrer.
     *
     * <p><strong>Recorded rather than omitted</strong>, and that is what makes the
     * attribution rule expressible. "This visitor came back directly on Tuesday" is a
     * fact the rule has to be able to see in order to decide not to attribute the
     * pledge to it; a visit that produced no row would be indistinguishable from a
     * visit that never happened.
     */
    DIRECT,

    /**
     * A creator's own share link, carrying the code §4.6's Promotion tab issues.
     *
     * <p>The one channel that names a specific link rather than a kind of place, and
     * the one a creator is paying attention to when they ask which of their
     * collaborators actually brought people.
     */
    REFERRAL_LINK,

    /** A social network, by whatever route — a post, a story, a message. */
    SOCIAL,

    /** A newsletter, an update notice, a personal mail carrying a link. */
    EMAIL,

    /** A search engine. */
    SEARCH,

    /** Advertising that was paid for, which is the only channel with a cost to compare against. */
    PAID,

    /**
     * A source that is none of the above and was still named.
     *
     * <p>Not a bin for unlabelled traffic — {@link #DIRECT} is that, and a source is
     * still required here. This is a press article, a forum, a partner site: places
     * worth reporting separately and not worth a channel each.
     */
    OTHER
}
