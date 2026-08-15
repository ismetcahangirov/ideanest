package az.ideanest.auth.application;

import az.ideanest.auth.domain.IdentityProvider;

/**
 * A provider this build knows about, which this environment has not been given
 * credentials for.
 *
 * <p>Not a start-up failure. Refusing to boot without every provider configured
 * would mean a missing Apple client identifier takes the whole API down —
 * including password sign-in, discovery, and pledges — over a feature nobody has
 * called yet. A staging environment without Apple credentials is a normal state,
 * not a broken one.
 *
 * <p>Configured <em>in part</em> is the opposite case, and {@code JwksOidcIdentityVerifier}
 * does refuse to start on it: an issuer with no audience is not a decision
 * anybody made, and it would otherwise fail as a puzzling 401 at the worst
 * moment.
 */
public class ProviderNotConfiguredException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient IdentityProvider provider;

    public ProviderNotConfiguredException(IdentityProvider provider) {
        super("Signing in with " + provider.key() + " is not available here.");
        this.provider = provider;
    }

    public IdentityProvider provider() {
        return provider;
    }
}
