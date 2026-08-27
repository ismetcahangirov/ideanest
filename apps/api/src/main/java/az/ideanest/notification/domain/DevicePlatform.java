package az.ideanest.notification.domain;

import java.util.Locale;
import java.util.Optional;

/**
 * Which store an installation came from — issue #87.
 *
 * <p><strong>It does not route anything.</strong> Expo's push service decides whether a
 * token goes to APNs or to FCM from the token itself, so this column plays no part in
 * delivery and could have been left out.
 *
 * <p>It is here because the most common shape of a push incident is "it stopped working
 * on one platform", and that question is unanswerable without it. The set is closed in
 * the schema too ({@code push_devices_platform_known}); a third platform is a migration
 * and a decision rather than a string a client passed in.
 */
public enum DevicePlatform {
    IOS,
    ANDROID;

    /**
     * The platform a client named, case-insensitively, or empty when it named something
     * else.
     *
     * <p>Empty rather than a default: a registration naming a platform this service does
     * not know is a client that is newer than this build, and quietly recording it as iOS
     * would make the one column this table exists to answer with into a guess.
     */
    public static Optional<DevicePlatform> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(value.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException unknown) {
            return Optional.empty();
        }
    }
}
