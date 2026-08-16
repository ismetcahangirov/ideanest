package az.ideanest.discovery.domain;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Where a campaign is, as §4.3's location filter: "country, city, or proximity".
 *
 * <p><strong>None of it is implementable yet, and the reason is the schema rather
 * than the effort.</strong> §7.2 lists {@code location_id} and {@code geo_point} on
 * {@code projects}; V6 left both out on purpose and said so, and no
 * {@code locations} table exists anywhere in the migration directory. There is no
 * column to compare against, so a country filter cannot narrow anything and a
 * radius cannot be measured from anything.
 *
 * <p>The shape is here so that #47 fills it in rather than inventing it, and so
 * that a caller who sends {@code country=AZ} today is refused with a problem detail
 * that names the issue instead of being handed every campaign on the platform. See
 * {@link DiscoveryCapability#FILTER_LOCATION} and
 * {@link DiscoveryCapability#FILTER_PROXIMITY}.
 *
 * @param countries ISO 3166-1 alpha-2, upper case. OR'd: a campaign has one country
 * @param cities free text, compared case-insensitively once there is a column. OR'd
 * @param proximity a point and a radius, or null. #47
 */
public record LocationFilter(Set<String> countries, Set<String> cities, Proximity proximity) {

    /** No constraint on where a campaign is. */
    public static final LocationFilter ANYWHERE = new LocationFilter(Set.of(), Set.of(), null);

    /**
     * A point and a radius around it, in kilometres.
     *
     * <p>Kilometres rather than metres because that is the unit a radius control is
     * labelled in, and {@code BigDecimal} rather than {@code double} because a
     * bound that arrives as a string should not be turned into a float on the way
     * in only to be turned back on the way to PostGIS.
     */
    public record Proximity(BigDecimal latitude, BigDecimal longitude, BigDecimal radiusKilometres) {

        public Proximity {
            if (latitude == null || longitude == null || radiusKilometres == null) {
                throw new IllegalArgumentException("A proximity filter needs a point and a radius");
            }
            if (latitude.abs().compareTo(new BigDecimal("90")) > 0) {
                throw new IllegalArgumentException("A latitude is between -90 and 90");
            }
            if (longitude.abs().compareTo(new BigDecimal("180")) > 0) {
                throw new IllegalArgumentException("A longitude is between -180 and 180");
            }
            if (radiusKilometres.signum() <= 0) {
                throw new IllegalArgumentException("A radius is positive");
            }
        }
    }

    public LocationFilter {
        countries = normalise(countries, true);
        cities = normalise(cities, false);
    }

    public boolean isAnywhere() {
        return countries.isEmpty() && cities.isEmpty() && proximity == null;
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
