package az.ideanest.discovery.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Where a campaign is, as §4.3's location filter: "country, city, or proximity".
 *
 * <p><strong>Served since #47.</strong> V16 brought the {@code locations} table,
 * {@code projects.location_id}, and the {@code cube}/{@code earthdistance} arithmetic
 * behind the distance; {@link DiscoveryCapability#FILTER_LOCATION} and
 * {@link DiscoveryCapability#FILTER_PROXIMITY} are declared by
 * {@code PostgresSearchService} and the refusals this record was originally a
 * placeholder for are gone.
 *
 * <p><strong>Country and city are one dimension, not two.</strong> §4.3's table has
 * one row for Location and it names all three controls, and the facet panel counts
 * them as one — for the reason category and subcategory are one dimension there: a
 * caller who picked a city and was shown country counts computed under the city
 * filter would see zero beside every country but one, which is the dead end that the
 * exclude-own-dimension rule exists to prevent, arriving through the back door.
 *
 * @param countries ISO 3166-1 alpha-2, upper case. OR'd: a campaign is in one country
 * @param cities the {@code locations.slug} of each place, OR'd. <strong>Folded</strong>
 *     by §11.3's fold before it gets here, so {@code Bakı}, {@code BAKI} and
 *     {@code baki} are one filter. A localised exonym is not — see
 *     {@code DiscoveryQueryBinder}
 * @param proximity an origin and, optionally, a radius around it, or null
 */
public record LocationFilter(Set<String> countries, Set<String> cities, Proximity proximity) {

    /** No constraint on where a campaign is. */
    public static final LocationFilter ANYWHERE = new LocationFilter(Set.of(), Set.of(), null);

    /**
     * An origin, and optionally a radius around it in kilometres.
     *
     * <p>Kilometres rather than metres because that is the unit a radius control is
     * labelled in, and {@code BigDecimal} rather than {@code double} throughout
     * because a bound that arrives as a string should not become a float on the way in
     * only to be turned back on the way to the database — the same rule the money
     * bounds are held to, for the same reason: the distance derived from this origin
     * is the keyset cursor's sort key and is compared for exact equality.
     *
     * <h2>The precision is a privacy decision</h2>
     *
     * <p><strong>An incoming point is quantised to {@link #COORDINATE_SCALE} decimal
     * places, here, before it can reach a query, a fingerprint, a cursor, an ETag, or
     * a log line.</strong> Two places is about 1.1 km at the equator and about 850 m
     * at Baku's latitude. Three arguments, and they agree:
     *
     * <ol>
     *   <li><strong>It is finer than the data it is measured against.</strong> A
     *       campaign is located at a city, and a city is one centroid standing for the
     *       whole place. Accepting six decimal places would be claiming a precision of
     *       ten centimetres against points that are accurate to kilometres — a
     *       precision the answer does not have and cannot acquire.
     *   <li><strong>Precise coordinates in a query string do not stay in the query
     *       string.</strong> They land in access logs, in {@code Referer} headers on
     *       every outbound link from the results page, in browser history, and in
     *       every shared cache between the reader and the origin. §17.4 redacts
     *       personal data from logs; the cheapest way to keep a doorstep out of a log
     *       is to refuse to accept one. A point rounded to a kilometre names a
     *       neighbourhood, which is all "near me" ever needed.
     *   <li><strong>A public cache that varies by exact coordinates is a cache that
     *       never hits.</strong> {@code /v1/discover} is unauthenticated and
     *       {@code Cache-Control: public, max-age=60}, and that shared response is
     *       what makes §20's thousand requests a second reachable. At six decimal
     *       places every reader has a cache key of their own and the hit rate is zero;
     *       at two, everybody within a kilometre shares one entry, and the populated
     *       part of the country is a few thousand keys rather than unbounded.
     * </ol>
     *
     * <p>Rounded rather than refused: a client that sent full GPS precision gets a
     * working feed centred on the same neighbourhood, and telling it off would only
     * teach it to round the value itself and send the same thing. What matters is that
     * nothing downstream ever sees the original.
     *
     * @param latitude quantised; between -90 and 90
     * @param longitude quantised; between -180 and 180
     * @param radiusKilometres null for "no bound, just order by distance". When
     *     present it is positive and at most {@link #MAX_RADIUS_KILOMETRES}, and the
     *     boundary is <strong>inclusive</strong>: a campaign at exactly the radius is
     *     inside it
     */
    public record Proximity(BigDecimal latitude, BigDecimal longitude, BigDecimal radiusKilometres) {

        /** Decimal places an origin is accepted at. See the record comment; this is a privacy bound. */
        public static final int COORDINATE_SCALE = 2;

        /**
         * Decimal places a radius is kept to — millimetres, expressed in kilometres.
         *
         * <p>Not privacy; two other things. Canonicalisation first:
         * {@code radiusKm=10} and {@code radiusKm=10.0} are the same filter and must
         * fingerprint to the same cursor, or a client that reformatted its own number
         * mid-scroll would be told its cursor does not match.
         *
         * <p>And it is deliberately the <em>same</em> precision the distance is rounded
         * to in the query — a millimetre — so that "exactly at the radius" is a value a
         * client can actually send. A coarser radius would make the inclusive boundary
         * documented on this record untestable in principle: no request could ever land
         * on it, and "inclusive" would be a claim about an unreachable case.
         */
        public static final int RADIUS_SCALE = 6;

        /**
         * The largest radius that means anything, in kilometres.
         *
         * <p><strong>Five hundred, and the bound is the point.</strong> Azerbaijan is
         * roughly 500 km across at its widest, so a circle this size already contains
         * every campaign the platform has; beyond it the filter selects everything and
         * charges for the arithmetic, which is what "everywhere, slowly" looks like as
         * a query parameter. A reader who wants a wider net wants the country filter,
         * which is a different control that answers the same wish exactly.
         *
         * <p>Refused rather than clamped, unlike {@code limit}. Clamping a limit gives
         * a client a working page; clamping a radius would silently <em>narrow</em>
         * somebody's search and return fewer campaigns than they asked to see, which
         * is a change of meaning in the direction that hides results.
         */
        public static final BigDecimal MAX_RADIUS_KILOMETRES = new BigDecimal("500");

        private static final BigDecimal MAX_LATITUDE = new BigDecimal("90");
        private static final BigDecimal MAX_LONGITUDE = new BigDecimal("180");

        public Proximity {
            if (latitude == null || longitude == null) {
                throw new IllegalArgumentException("A proximity filter needs an origin");
            }
            if (latitude.abs().compareTo(MAX_LATITUDE) > 0) {
                throw new IllegalArgumentException("A latitude is between -90 and 90");
            }
            if (longitude.abs().compareTo(MAX_LONGITUDE) > 0) {
                throw new IllegalArgumentException("A longitude is between -180 and 180");
            }
            // HALF_EVEN rather than HALF_UP: the rounding happens to every request on
            // the platform, and a mode that always rounds .005 away from zero would
            // shift the whole grid of cache keys a fraction north-east.
            latitude = latitude.setScale(COORDINATE_SCALE, RoundingMode.HALF_EVEN);
            longitude = longitude.setScale(COORDINATE_SCALE, RoundingMode.HALF_EVEN);
            if (radiusKilometres != null) {
                if (radiusKilometres.signum() <= 0) {
                    throw new IllegalArgumentException("A radius is positive");
                }
                if (radiusKilometres.compareTo(MAX_RADIUS_KILOMETRES) > 0) {
                    throw new IllegalArgumentException(
                            "A radius is at most " + MAX_RADIUS_KILOMETRES.toPlainString() + " km");
                }
                radiusKilometres = radiusKilometres.setScale(RADIUS_SCALE, RoundingMode.HALF_EVEN);
            }
        }

        /** The radius in metres, which is the unit {@code earth_distance} answers in. */
        public BigDecimal radiusMetres() {
            return radiusKilometres == null ? null : radiusKilometres.multiply(new BigDecimal("1000"));
        }

        /**
         * The form the query fingerprint — and therefore the cursor — is bound to.
         *
         * <p><strong>This is how the origin is pinned.</strong> #42 pinned the decay
         * clock in the cursor and #44 pinned a digest of the ranking weights, each
         * because a sort key that can move between page one and page two makes a scroll
         * duplicate and drop cards silently. The origin is the same hazard in its
         * purest form: every distance on the feed is measured from it, so a client
         * whose location moved by one quantised step mid-scroll would resume a keyset
         * from a number that no longer picks out the row it was written for. It is part
         * of {@code DiscoveryQuery.fingerprint()}, so a cursor issued from one origin
         * and replayed against another is refused with
         * {@code DISCOVERY_CURSOR_MISMATCH} and the client restarts.
         *
         * <p>Written out here rather than left to {@code toString()} so the value is a
         * canonical decimal rather than whatever a record's generated string form
         * happens to be — {@code 40.4} and {@code 40.40} must not be two fingerprints.
         *
         * <p>Distance from a fixed origin is otherwise the most stable sort key in this
         * module: unlike {@code pledged_amount} it cannot change while a reader scrolls,
         * because neither the origin nor a city centroid moves. Pinning the origin is
         * what makes that true rather than nearly true.
         */
        public String canonical() {
            return latitude.toPlainString()
                    + "," + longitude.toPlainString()
                    + "," + (radiusKilometres == null ? "" : radiusKilometres.toPlainString());
        }
    }

    public LocationFilter {
        countries = normalise(countries, true);
        cities = normalise(cities, false);
    }

    public boolean isAnywhere() {
        return countries.isEmpty() && cities.isEmpty() && proximity == null;
    }

    /** Whether anything here narrows which campaigns come back, as opposed to only ordering them. */
    public boolean narrows() {
        return !countries.isEmpty()
                || !cities.isEmpty()
                || (proximity != null && proximity.radiusKilometres() != null);
    }

    private static Set<String> normalise(Set<String> values, boolean upperCase) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        Set<String> cleaned = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            String trimmed = value.trim();
            cleaned.add(upperCase ? trimmed.toUpperCase(Locale.ROOT) : trimmed);
        }
        return Set.copyOf(cleaned);
    }
}
