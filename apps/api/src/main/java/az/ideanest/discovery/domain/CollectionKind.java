package az.ideanest.discovery.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What a curated list is, in the three senses §4.3 and D-08 describe.
 *
 * <p>One table with a kind rather than three tables, for the reason V14 gives: all
 * three have a slug in a URL, a translated title and description, a publication
 * decision, an optional window, cover imagery, and an edited sequence of campaigns
 * behind them. The kind is what the list means to a reader, and it is the only thing
 * that differs.
 *
 * <p>The names are the database's, and the wire values are lower case with
 * underscores like every other closed vocabulary this module publishes —
 * {@code DiscoveryStatus}, {@code CompletionBand}, {@code AmountBand}.
 */
public enum CollectionKind {

    /**
     * "Staff picks": what the platform itself stands behind.
     *
     * <p>The usual carrier of the editorial badge (§3.2), though the badge is a
     * separate decision — see {@code collections.grants_badge}, and V14 for why
     * granting a badge is not implied by the kind.
     */
    STAFF_SELECTION("staff_selection"),

    /** A season, a subject, an anniversary. Often has an expiry and no opening. */
    THEMED("themed"),

    /**
     * §4.3's Programmes: a themed open call with a window it is open in.
     *
     * <p>This is the kind the {@code programme} filter on {@code /v1/discover}
     * narrows to. The other two are collections a reader browses rather than
     * programmes a campaign is part of, so filtering the feed by one would be
     * filtering by "was picked", which is what {@code showOnly=featured} already
     * says.
     */
    OPEN_CALL("open_call");

    private static final Map<String, CollectionKind> BY_WIRE_VALUE = byWireValue();

    private final String wireValue;

    CollectionKind(String wireValue) {
        this.wireValue = wireValue;
    }

    /** What a client sends and reads back. */
    public String wireValue() {
        return wireValue;
    }

    public static Optional<CollectionKind> fromWireValue(String value) {
        return Optional.ofNullable(value).map(BY_WIRE_VALUE::get);
    }

    /** The stored form, which is the enum name. */
    public static Optional<CollectionKind> fromStorageValue(String value) {
        if (value == null) {
            return Optional.empty();
        }
        for (CollectionKind kind : values()) {
            if (kind.name().equals(value)) {
                return Optional.of(kind);
            }
        }
        return Optional.empty();
    }

    public static List<String> wireValues() {
        return List.copyOf(BY_WIRE_VALUE.keySet());
    }

    private static Map<String, CollectionKind> byWireValue() {
        Map<String, CollectionKind> map = new LinkedHashMap<>();
        for (CollectionKind kind : values()) {
            map.put(kind.wireValue, kind);
        }
        return Collections.unmodifiableMap(map);
    }
}
