package az.ideanest.discovery.application;

import az.ideanest.discovery.domain.DiscoveryCapability;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * The caller asked for something §4.3 describes and no implementation can do yet.
 *
 * <p>A 400 rather than a 501: the request is well formed and the service is not
 * broken, but it names an option that does not exist here, and 501 would tell a
 * client to retry against another instance. The body lists every missing capability
 * at once rather than the first — a client that sent both {@code q} and
 * {@code sort=relevance} should learn about both in one round trip rather than fix
 * one and be refused again.
 *
 * <p>The alternative is a silent fallback, which this whole mechanism exists to
 * prevent. See {@link DiscoveryCapability}.
 */
public class UnsupportedDiscoveryOptionException extends RuntimeException {

    private final Set<DiscoveryCapability> missing;

    public UnsupportedDiscoveryOptionException(Set<DiscoveryCapability> missing) {
        super("Unsupported discovery options: " + missing);
        this.missing = missing.isEmpty()
                ? Collections.unmodifiableSet(EnumSet.noneOf(DiscoveryCapability.class))
                : Collections.unmodifiableSet(EnumSet.copyOf(missing));
    }

    public Set<DiscoveryCapability> missing() {
        return missing;
    }

    /**
     * Refuses a query that needs more than the implementation has.
     *
     * <p>Here rather than in a controller because there are two of them —
     * {@code /v1/discover} and {@code /v1/search} — and a second copy of this check
     * is a second place for somebody to leave a capability out of the comparison.
     * The failure that would produce is the exact one {@link DiscoveryCapability}
     * exists to prevent, arriving on whichever endpoint was forgotten.
     *
     * @param required from {@link DiscoveryQuery#requiredCapabilities()}
     * @param available from {@link SearchService#capabilities()}
     * @throws UnsupportedDiscoveryOptionException naming <em>every</em> missing
     *     capability rather than the first, so a client that asked for two things
     *     learns about both in one round trip
     */
    public static void requireSupported(
            Set<DiscoveryCapability> required, Set<DiscoveryCapability> available) {
        Set<DiscoveryCapability> missing = EnumSet.noneOf(DiscoveryCapability.class);
        missing.addAll(required);
        missing.removeAll(available);
        if (!missing.isEmpty()) {
            throw new UnsupportedDiscoveryOptionException(missing);
        }
    }
}
