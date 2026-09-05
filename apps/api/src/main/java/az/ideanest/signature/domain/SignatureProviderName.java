package az.ideanest.signature.domain;

/**
 * The national signature providers this platform knows about — V67's {@code provider},
 * issue #428.
 *
 * <p><strong>Closed, and closed in two places</strong>, for {@code DocumentKind}'s reason:
 * this enum and V67's {@code signatures_provider_known} say the same names, because either
 * alone is half a rule.
 *
 * <p>One value today. It exists as an enum rather than as a constant because §9.4's argument
 * about payment providers applies unchanged here: a second provider is only a day's work if
 * the first one's vocabulary never leaked, and a column added on the day the second arrives is
 * a column every historical row has to be assumed into.
 */
public enum SignatureProviderName {

    /**
     * SİMA İmza — the mobile signature issued against a citizen's state-verified identity.
     *
     * <p>ASAN İmza is the obvious second and is not here, because a value with no adapter is a
     * configuration a deployment can name and cannot honour.
     */
    SIMA_IMZA;

    /**
     * The name a deployment configured, or a refusal.
     *
     * <p>{@link IllegalArgumentException} rather than an empty optional, following
     * {@code ProviderName.of}: this is only ever called on a value out of configuration, and a
     * deployment naming a provider that does not exist is a start-up failure rather than a
     * platform that quietly signs nothing.
     */
    public static SignatureProviderName of(String configured) {
        for (SignatureProviderName name : values()) {
            if (name.name().equalsIgnoreCase(configured)) {
                return name;
            }
        }
        throw new IllegalArgumentException(
                "%s is not a signature provider this platform knows about".formatted(configured));
    }
}
