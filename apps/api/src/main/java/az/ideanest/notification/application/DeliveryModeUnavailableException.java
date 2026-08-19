package az.ideanest.notification.application;

import az.ideanest.notification.domain.DeliveryMode;
import az.ideanest.notification.domain.NotificationChannel;
import java.util.Objects;

/**
 * A mode this channel does not have.
 *
 * <p>{@link DeliveryMode#DIGEST} on {@link NotificationChannel#IN_APP}, which is the only
 * combination there is: an inbox is already a list of things in one place, so digesting it
 * would combine a list into a list. {@code notification_preferences_in_app_does_not_digest}
 * refuses the row as well; this refuses the request, so the caller is told which switch
 * was wrong instead of receiving a constraint violation.
 *
 * <p><strong>Refused rather than clamped, which is the opposite of what
 * {@code DeliveryPolicy} does with the same combination.</strong> The two are answering
 * different questions and both answers are right. The policy resolves a value that is
 * already stored — arrived by a migration, by an older release, by any route — and clamps
 * it to {@code IMMEDIATE}, because the person asked to be told and the disagreement is
 * only about how. This is a request being made now, by somebody who can be told; storing
 * a value that is silently read as a different one would put the settings page and the
 * fan-out in permanent disagreement, and the person would never find out which won.
 */
public class DeliveryModeUnavailableException extends RuntimeException {

    private final NotificationChannel channel;

    private final DeliveryMode mode;

    public DeliveryModeUnavailableException(NotificationChannel channel, DeliveryMode mode) {
        super(Objects.requireNonNull(mode, "A refusal is about a mode") + " is not available on "
                + Objects.requireNonNull(channel, "A refusal is about a channel") + ".");
        this.channel = channel;
        this.mode = mode;
    }

    public NotificationChannel channel() {
        return channel;
    }

    public DeliveryMode mode() {
        return mode;
    }
}
