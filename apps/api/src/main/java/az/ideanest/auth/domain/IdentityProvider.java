package az.ideanest.auth.domain;

import java.util.Locale;
import java.util.Optional;

/**
 * A provider a person can sign in with.
 *
 * <p>A closed set rather than a free string. The value reaches a database check
 * constraint, a configuration key, and a URL path segment; an open set would
 * make "which providers exist" a question three places answer differently.
 *
 * <p><strong>Apple is not optional once Google exists on iOS.</strong> The App
 * Store review guidelines require Sign in with Apple to be offered alongside any
 * other third-party sign-in, so shipping Google alone would ship an app that
 * cannot be published.
 */
public enum IdentityProvider {
    GOOGLE,
    APPLE;

    /**
     * The provider named by a URL path segment, if it is one we know.
     *
     * <p>Empty rather than an exception: an unknown segment is a request for a
     * resource that does not exist, and the caller is told exactly that.
     */
    public static Optional<IdentityProvider> parse(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(name.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** The lowercase form used in configuration keys and in the URL. */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }
}
