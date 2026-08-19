package az.ideanest.notification.application;

import az.ideanest.notification.domain.DeliveryMode;
import az.ideanest.notification.domain.DeliveryPolicy;
import az.ideanest.notification.domain.NotificationCategory;
import az.ideanest.notification.domain.NotificationChannel;
import java.util.Objects;

/**
 * One switch on the settings page: what this account gets on this category and channel,
 * and what it may change it to.
 *
 * <p><strong>The mode is the resolved one, never the stored one.</strong>
 * {@code DeliveryPolicy} is what turns an absent row into an answer, and the absent row is
 * the common case — a user who has never opened the page has no rows at all. A page fed
 * the stored values would render twenty-one switches in an undefined position and then
 * disagree with what the fan-out actually does.
 *
 * @param mode what happens today, resolved through {@code DeliveryPolicy.resolveFor}
 * @param stored whether there is a row behind it. Not cosmetic: "IMMEDIATE because we
 *     chose it for them" and "IMMEDIATE because they chose it" are different facts —
 *     {@code DeliveryPolicy}'s class comment is about that distinction — and only the
 *     second survives a change of policy. A client can use it to show a default as a
 *     default
 * @param changeable false for a mandatory category. The switch is drawn disabled rather
 *     than absent, because a security alert the account holder cannot silence is
 *     information they should have; a missing row reads as an oversight
 * @param digestOffered whether {@link DeliveryMode#DIGEST} is one of the choices here.
 *     False on in-app, where a digest of a list would be a list, and false on a mandatory
 *     category. On the page so the control can be built from the response instead of the
 *     client reimplementing §4.10's rules and drifting from them
 */
public record ResolvedPreference(
        NotificationCategory category,
        NotificationChannel channel,
        DeliveryMode mode,
        boolean stored,
        boolean changeable,
        boolean digestOffered) {

    public ResolvedPreference {
        Objects.requireNonNull(category, "A preference is about a category");
        Objects.requireNonNull(channel, "A preference is about a channel");
        Objects.requireNonNull(mode, "A preference resolves to some mode");
    }

    /**
     * The switch for one (category, channel), given whatever is stored.
     *
     * @param stored what the recipient asked for, or null when they have never said
     */
    public static ResolvedPreference of(
            NotificationCategory category, NotificationChannel channel, DeliveryMode stored) {

        boolean changeable = !category.isMandatory();
        return new ResolvedPreference(
                category,
                channel,
                DeliveryPolicy.resolveFor(category, channel, stored),
                stored != null,
                changeable,
                changeable && channel.isDigestible());
    }
}
