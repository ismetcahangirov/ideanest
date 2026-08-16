package az.ideanest.discovery.api;

import az.ideanest.discovery.application.DiscoveryQuery;
import az.ideanest.discovery.application.UnknownFilterValueException;
import az.ideanest.discovery.domain.AmountBand;
import az.ideanest.discovery.domain.AmountRange;
import az.ideanest.discovery.domain.CompletionBand;
import az.ideanest.discovery.domain.DiscoveryCursor;
import az.ideanest.discovery.domain.DiscoverySort;
import az.ideanest.discovery.domain.DiscoveryStatus;
import az.ideanest.discovery.domain.LocationFilter;
import az.ideanest.discovery.domain.ShowOnly;
import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.springframework.util.MultiValueMap;

/**
 * Turns a query string into a {@link DiscoveryQuery}.
 *
 * <p>Written by hand rather than bound by Spring, for two reasons. Spring's binder
 * answers an unparseable enum with a 400 whose body names a Java type and a field
 * path, which is not something a client can act on and not the RFC 9457 shape §10.4
 * requires; and every multi-valued filter here has to accept both
 * {@code ?tag=a&tag=b} and {@code ?tag=a,b}, because D-12 makes filter URLs shareable
 * and a shared URL is the comma form.
 *
 * <p><strong>Unknown values from a closed vocabulary are refused; unknown slugs are
 * not.</strong> See {@link UnknownFilterValueException}.
 */
final class DiscoveryQueryBinder {

    private DiscoveryQueryBinder() {
    }

    static DiscoveryQuery bind(MultiValueMap<String, String> parameters, String locale) {
        DiscoveryQuery.Builder builder = DiscoveryQuery.builder()
                .locale(locale)
                .text(single(parameters, "q"))
                .statuses(closedSet(parameters, "status", DiscoveryStatus::fromWireValue, DiscoveryStatus.wireValues()))
                .categorySlugs(openSet(parameters, "category"))
                .subcategorySlugs(openSet(parameters, "subcategory"))
                .tagSlugs(openSet(parameters, "tag"))
                // §4.3's Programmes. An open vocabulary like `category` and `tag`
                // and for the same reason: the slugs are content the platform's own
                // curators create, so a link shared after a programme was renamed
                // gets an empty feed rather than a 400. Singular and comma-separable
                // like every other multi-valued filter here.
                .programmeSlugs(openSet(parameters, "programme"))
                .completion(closedSet(parameters, "completion", CompletionBand::fromWireValue, CompletionBand.wireValues()))
                .goal(amountRange(parameters, "goalBand", "goalMin", "goalMax"))
                .raised(amountRange(parameters, "raisedBand", "raisedMin", "raisedMax"))
                .location(new LocationFilter(openSet(parameters, "country"), openSet(parameters, "city"), null))
                .showOnly(closedSet(parameters, "showOnly", ShowOnly::fromWireValue, ShowOnly.wireValues()));

        String sort = single(parameters, "sort");
        if (sort != null) {
            // `isClientSelectable` filters out DiscoverySort.CURATED, which names the
            // order the collection landing page is served in and means nothing here:
            // outside a collection there is no curator and no position. Refused by the
            // same path as a misspelt sort, with the same list of what is accepted, so
            // it is not a special error a client has to learn about.
            builder.sort(DiscoverySort.fromWireValue(sort)
                    .filter(DiscoverySort::isClientSelectable)
                    .orElseThrow(() -> new UnknownFilterValueException("sort", sort, DiscoverySort.wireValues())));
        }

        String limit = single(parameters, "limit");
        if (limit != null) {
            try {
                // Clamped by DiscoveryQuery rather than refused here: a client asking
                // for two hundred cards wants a page, and giving it a hundred is more
                // useful than giving it an error. A limit that is not a number is a
                // different thing and is refused.
                builder.limit(Integer.parseInt(limit.trim()));
            } catch (NumberFormatException e) {
                throw new UnknownFilterValueException("limit", limit, List.of());
            }
        }

        String cursor = single(parameters, "cursor");
        if (cursor != null) {
            // Decoded here so that a malformed cursor fails before a query runs.
            // Whether it belongs to *this* query is checked by the search service,
            // which is the only thing that knows the fingerprint.
            builder.cursor(DiscoveryCursor.decode(cursor));
        }

        return builder.build();
    }

    /**
     * Values of a closed vocabulary the API defines.
     *
     * @param known what a value maps to, or empty when it is not one of them
     * @param allowed every accepted value, for the error body
     */
    private static <E extends Enum<E>> Set<E> closedSet(
            MultiValueMap<String, String> parameters,
            String parameter,
            Function<String, Optional<E>> known,
            List<String> allowed) {

        Set<String> raw = openSet(parameters, parameter);
        if (raw.isEmpty()) {
            return Set.of();
        }
        Set<E> values = new LinkedHashSet<>();
        for (String value : raw) {
            values.add(known.apply(value)
                    .orElseThrow(() -> new UnknownFilterValueException(parameter, value, allowed)));
        }
        return values.isEmpty() ? Set.of() : EnumSet.copyOf(values);
    }

    /**
     * Values of an open vocabulary the platform's content defines — slugs.
     *
     * <p>Accepts repetition and comma separation, and both at once. Blank entries are
     * dropped rather than refused: {@code ?tag=} is what a client sends when its
     * filter control is empty, and a 400 for it would make an empty filter an error.
     */
    private static Set<String> openSet(MultiValueMap<String, String> parameters, String parameter) {
        List<String> values = parameters.get(parameter);
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        Set<String> result = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null) {
                continue;
            }
            for (String part : value.split(",")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    result.add(trimmed);
                }
            }
        }
        return result;
    }

    private static AmountRange amountRange(
            MultiValueMap<String, String> parameters, String bandParameter, String minParameter, String maxParameter) {
        Set<AmountBand> bands =
                closedSet(parameters, bandParameter, AmountBand::fromWireValue, AmountBand.wireValues());
        BigDecimal min = amount(parameters, minParameter);
        BigDecimal max = amount(parameters, maxParameter);
        try {
            return new AmountRange(bands, min, max);
        } catch (IllegalArgumentException e) {
            // A minimum above the maximum, or a negative bound. Reported against the
            // pair rather than against one of them, because neither is wrong alone.
            throw new UnknownFilterValueException(minParameter + "/" + maxParameter, min + ".." + max, List.of());
        }
    }

    /**
     * A money bound, as a string.
     *
     * <p>§10.3 and {@code CLAUDE.md}: money crosses the wire as a string and is
     * {@code BigDecimal} inside. A filter bound is money — it is compared against
     * {@code goal_amount} — so it is parsed the same way, and never through
     * {@code Double.parseDouble}, which would put a campaign on the wrong side of a
     * boundary it sits exactly on.
     */
    private static BigDecimal amount(MultiValueMap<String, String> parameters, String parameter) {
        String value = single(parameters, parameter);
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            throw new UnknownFilterValueException(parameter, value, List.of());
        }
    }

    /** The first value, or null. A repeated single-valued parameter takes the first. */
    private static String single(MultiValueMap<String, String> parameters, String parameter) {
        String value = parameters.getFirst(parameter);
        return value == null || value.isBlank() ? null : value;
    }
}
