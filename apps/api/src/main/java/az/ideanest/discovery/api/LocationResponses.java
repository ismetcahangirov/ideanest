package az.ideanest.discovery.api;

import az.ideanest.discovery.domain.Place;
import java.util.List;

/**
 * What {@code GET /v1/locations} answers with — §4.3's location vocabulary (#276).
 *
 * <p>Separate from {@link Place} for the reason {@link DiscoveryResponses} and
 * {@link CollectionResponses} are: a rename inside the module is not a breaking API change,
 * and the wire format is legible in one file.
 */
public final class LocationResponses {

    private LocationResponses() {
    }

    /**
     * One place a campaign or a person can be in.
     *
     * @param slug what {@code ?city=} and {@code PATCH /v1/me/profile}'s {@code
     *     locationSlug} match on
     * @param name in the negotiated language, never absent
     */
    public record Location(String slug, String name) {
    }

    /**
     * {@code GET /v1/locations}.
     *
     * <p><strong>An object with one array rather than a bare array</strong>, the shape
     * {@code CollectionIndex} takes and for its reason: a bare array is a body that cannot
     * grow a field, and the first thing this one will want is a country grouping — §4.3's
     * location filter is one dimension with three controls, and only the middle one is
     * published here today.
     */
    public record LocationIndex(List<Location> items) {
    }

    public static LocationIndex index(List<Place> places) {
        return new LocationIndex(
                places.stream().map(place -> new Location(place.slug(), place.name())).toList());
    }
}
