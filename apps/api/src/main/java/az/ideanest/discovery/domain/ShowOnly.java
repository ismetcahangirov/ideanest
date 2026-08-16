package az.ideanest.discovery.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * §4.3's "Show only" filter: recommended, editorially featured, saved.
 *
 * <p>All three need something that does not exist. Each is declared so that the
 * issue which owns it adds one line to {@code PostgresSearchService.capabilities()}
 * rather than widening the query object, and so that asking for one today is
 * refused rather than silently ignored — a backer who filtered to their saved
 * campaigns and was shown the whole platform has been lied to about which of these
 * cards they chose.
 */
public enum ShowOnly {

    /**
     * Campaigns this caller saved.
     *
     * <p><strong>There is no saved-projects table.</strong> §7.2 names {@code saves}
     * beside {@code follows} and {@code reminders} as a backer signal, and V10
     * created only {@code reminders}. This is also the only filter in the whole
     * query that would make the response depend on who is asking, which is why the
     * endpoint can be {@code Cache-Control: public} today and must not stay so once
     * this works. See {@code DiscoveryController}.
     */
    SAVED("saved", DiscoveryCapability.FILTER_SAVED),

    /** Personalised recommendations. §11.2's {@code w6}, which is #44. */
    RECOMMENDED("recommended", DiscoveryCapability.FILTER_RECOMMENDED),

    /**
     * Editorially featured campaigns.
     *
     * <p>{@code projects.is_featured} is listed in §7.2 and V6 left it out saying
     * "curation is an editorial workflow, not a boolean somebody sets by hand".
     * #48 brings the workflow and the column together.
     */
    FEATURED("featured", DiscoveryCapability.FILTER_FEATURED);

    private static final Map<String, ShowOnly> BY_WIRE_VALUE = byWireValue();

    private final String wireValue;
    private final DiscoveryCapability requiredCapability;

    ShowOnly(String wireValue, DiscoveryCapability requiredCapability) {
        this.wireValue = wireValue;
        this.requiredCapability = requiredCapability;
    }

    public String wireValue() {
        return wireValue;
    }

    public DiscoveryCapability requiredCapability() {
        return requiredCapability;
    }

    public static Optional<ShowOnly> fromWireValue(String value) {
        return Optional.ofNullable(value).map(BY_WIRE_VALUE::get);
    }

    public static List<String> wireValues() {
        return List.copyOf(BY_WIRE_VALUE.keySet());
    }

    private static Map<String, ShowOnly> byWireValue() {
        Map<String, ShowOnly> map = new LinkedHashMap<>();
        for (ShowOnly value : values()) {
            map.put(value.wireValue, value);
        }
        return Collections.unmodifiableMap(map);
    }
}
