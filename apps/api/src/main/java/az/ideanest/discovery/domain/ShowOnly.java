package az.ideanest.discovery.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * §4.3's "Show only" filter: recommended, editorially featured, saved.
 *
 * <p>Each is declared so that the issue which owns it adds one line to
 * {@code PostgresSearchService.capabilities()} rather than widening the query
 * object, and so that asking for one before it exists is refused rather than
 * silently ignored — a backer who filtered to their saved campaigns and was shown
 * the whole platform has been lied to about which of these cards they chose.
 *
 * <p><strong>{@link #FEATURED} works; the other two are still refused</strong>, and
 * that is the mechanism doing its job rather than an inconsistency: #48 brought the
 * curation schema and added one constant to the capability set, and nothing else in
 * this file or in the query object had to change.
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

    /**
     * Personalised recommendations. §11.2's {@code w6}, which is #44.
     *
     * <p><strong>Still refused, deliberately, and #48 did not take it.</strong> It
     * is the one filter here whose answer depends on who is asking, and answering it
     * needs the behavioural signals and the composite of §11.2 — none of which
     * curation has. Serving "featured" under this name would tell every reader that
     * the platform's staff picks are recommendations chosen for them.
     */
    RECOMMENDED("recommended", DiscoveryCapability.FILTER_RECOMMENDED),

    /**
     * Editorially featured campaigns. <strong>#48, and served.</strong>
     *
     * <p>{@code projects.is_featured} is listed in §7.2 and V6 left it out saying
     * "curation is an editorial workflow, not a boolean somebody sets by hand". It
     * is still not there: a campaign is featured exactly when it is in a published,
     * in-window collection that grants a badge, which is what V14's
     * {@code project_editorial_badges} view says and the only place it is said.
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
