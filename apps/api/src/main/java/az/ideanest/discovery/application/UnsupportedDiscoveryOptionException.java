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
}
