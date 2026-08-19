package az.ideanest.notification.infrastructure;

import az.ideanest.notification.domain.NotificationChannel;
import java.util.Objects;
import java.util.UUID;

/**
 * One person, one channel: the unit a digest is built for.
 *
 * <p>§4.10's preferences are per category and per channel, so a person can digest their
 * payments and be told about their pledges immediately. What a digest groups is therefore
 * (recipient, channel) and pointedly <em>not</em> (recipient, channel, category) — the
 * alternative would send somebody three separate digests on one channel at one moment, which
 * is three messages where they asked for fewer.
 *
 * <p>A record rather than an interface projection, because
 * {@code NotificationRepository.heldGroups} is a constructor expression: Hibernate builds
 * this directly and there is no proxy, no aliasing convention to get right, and no way for
 * the query and the type to drift without the query failing to compile.
 */
public record HeldGroup(UUID recipientId, NotificationChannel channel) {

    public HeldGroup {
        Objects.requireNonNull(recipientId, "A digest is for somebody");
        Objects.requireNonNull(channel, "A digest goes somewhere");
    }

    @Override
    public String toString() {
        // No recipient. "Who is digesting what" is a statement about a person (§17.4), and
        // this ends up in the log line about a digest that could not be sent.
        return "HeldGroup[channel=" + channel + "]";
    }
}
