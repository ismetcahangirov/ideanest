package az.ideanest.notification.application;

import az.ideanest.notification.domain.DeliveryMode;
import az.ideanest.notification.domain.NotificationCategory;
import az.ideanest.notification.domain.NotificationChannel;
import java.util.Objects;

/**
 * One instruction a caller is giving: this category, this channel, this mode.
 *
 * <p>The unit {@code PATCH /v1/me/notification-preferences} carries, and the reason that
 * endpoint is a {@code PATCH} rather than a {@code PUT}. A settings page sends the
 * switches somebody touched; a {@code PUT} would mean sending all twenty-one every time,
 * and any request that omitted one would silently reset it — which on a page saved from
 * two tabs is one tab quietly undoing the other.
 *
 * @param category which group of §4.10's rows
 * @param channel which of §4.10's three columns
 * @param mode what to do. {@link DeliveryMode#OFF}, {@link DeliveryMode#IMMEDIATE}, or
 *     {@link DeliveryMode#DIGEST} where the channel can digest
 */
public record PreferenceChange(NotificationCategory category, NotificationChannel channel, DeliveryMode mode) {

    public PreferenceChange {
        Objects.requireNonNull(category, "An instruction is about a category");
        Objects.requireNonNull(channel, "An instruction is about a channel");
        Objects.requireNonNull(mode, "An instruction says what to do");
    }
}
