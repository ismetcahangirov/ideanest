package az.ideanest.notification.domain;

/**
 * Where a notification goes: §4.10's three columns.
 *
 * <p>The set is closed and it is closed in the schema too —
 * {@code notifications_channel_known}. A fourth channel is a migration and a decision
 * rather than a string somebody passed in.
 *
 * <p><strong>Only one of the three has a transport.</strong> #86 is transactional email
 * and #87 is push; neither exists. That is deliberately not modelled here, because
 * whether a channel can currently be delivered is a property of the deployment rather
 * than of the channel — see {@code ChannelSender} and its implementations.
 */
public enum NotificationChannel {

    /**
     * The in-app inbox: §12.1's {@code user:{id}} channel and §10.2's
     * {@code GET /v1/me/notifications}.
     *
     * <p>The one channel where the notification row <em>is</em> the message. There is
     * nothing to serialise, nothing to hand to a provider, and nothing that can be
     * accepted and then lost.
     */
    IN_APP(false),

    /** #86. Every §4.10 row marked ✅ in the email column. */
    EMAIL(true),

    /** #87. Expo, over the platform push services — §14.4. */
    PUSH(true);

    private final boolean digestible;

    NotificationChannel(boolean digestible) {
        this.digestible = digestible;
    }

    /**
     * Whether "combine these into one message" means anything on this channel.
     *
     * <p>False for {@link #IN_APP}, and that is the whole of it: an inbox is already a
     * list of things in one place, so digesting it would combine a list into a list.
     * Refused by {@code notification_preferences_in_app_does_not_digest} as well, because
     * a setting nothing can honour is a setting that lies to the person who set it.
     */
    public boolean isDigestible() {
        return digestible;
    }
}
