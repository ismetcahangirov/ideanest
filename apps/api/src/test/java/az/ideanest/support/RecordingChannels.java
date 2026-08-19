package az.ideanest.support;

import az.ideanest.notification.application.ChannelSender;
import az.ideanest.notification.application.NotificationDigest;
import az.ideanest.notification.application.NotificationMessage;
import az.ideanest.notification.domain.NotificationChannel;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The channels a notification pass sends to, as a test controls them.
 *
 * <p>Handed to {@code NotificationSender.sendPending(Instant, Map)} and
 * {@code NotificationDigestJob.combineDue(Instant, Map)} rather than replacing the
 * {@code ChannelSender} beans, for the reason {@code NotificationDispatch} gives: replacing a
 * bean replaces it for the whole suite and splits the context cache, which in this codebase
 * means a second PostgreSQL container to prove something about backoff.
 *
 * <p><strong>Records both halves of the port.</strong> A double that implemented
 * {@link #send(NotificationMessage)} and left the digest to a default would be a double that
 * could not tell "the digest went out as one message" from "its members went out one at a time",
 * which is the whole property a digest has.
 *
 * <p>Not a Spring bean and not in {@code TestDoublesConfiguration}: it is constructed per test,
 * because "the email transport is refusing at this moment" is state a test sets rather than a
 * wiring decision.
 */
public final class RecordingChannels {

    /** What a refusing channel says, so that the dead letter can be checked for it. */
    public static final String REFUSAL = "the transport is unreachable";

    private final Map<NotificationChannel, ChannelSender> map = new EnumMap<>(NotificationChannel.class);

    private final List<UUID> sent = new ArrayList<>();

    private final List<Attempt> attempts = new ArrayList<>();

    private final List<NotificationDigest> digests = new ArrayList<>();

    private NotificationChannel refused;

    private RecordingChannels(NotificationChannel refused) {
        this.refused = refused;
        for (NotificationChannel channel : NotificationChannel.values()) {
            map.put(channel, new Recording(channel));
        }
    }

    public static RecordingChannels accepting() {
        return new RecordingChannels(null);
    }

    public static RecordingChannels refusing(NotificationChannel channel) {
        return new RecordingChannels(channel);
    }

    /** Stops refusing, which is what a transport coming back up looks like. */
    public void accept() {
        refused = null;
    }

    public Map<NotificationChannel, ChannelSender> map() {
        return map;
    }

    /** The notifications a channel accepted, in order. Includes those inside a digest. */
    public List<UUID> sent() {
        return List.copyOf(sent);
    }

    /** Every message handed to a channel, accepted or not. */
    public List<UUID> attemptedIds(NotificationChannel channel) {
        return attempts.stream()
                .filter(attempt -> attempt.channel() == channel)
                .map(Attempt::id)
                .toList();
    }

    /**
     * The digests a channel accepted, in order.
     *
     * <p>Accepted rather than attempted, so that a suite asserting "one message, not four"
     * cannot be satisfied by four refused attempts.
     */
    public List<NotificationDigest> digests() {
        return List.copyOf(digests);
    }

    private record Attempt(NotificationChannel channel, UUID id) {
    }

    private final class Recording implements ChannelSender {

        private final NotificationChannel channel;

        private Recording(NotificationChannel channel) {
            this.channel = channel;
        }

        @Override
        public NotificationChannel channel() {
            return channel;
        }

        @Override
        public void send(NotificationMessage message) {
            attempts.add(new Attempt(channel, message.id()));
            if (channel == refused) {
                throw new IllegalStateException(REFUSAL);
            }
            sent.add(message.id());
        }

        @Override
        public void send(NotificationDigest digest) {
            // The digest's own key is recorded as one attempt, because one message was
            // attempted. Its members go into `sent` as well, so that a test can assert both
            // "one message" and "these notifications went".
            attempts.add(new Attempt(channel, digest.id()));
            if (channel == refused) {
                throw new IllegalStateException(REFUSAL);
            }
            digests.add(digest);
            digest.notifications().forEach(message -> sent.add(message.id()));
        }
    }
}
