package az.ideanest.notification.infrastructure;

import az.ideanest.notification.application.ChannelSender;
import az.ideanest.notification.application.NotificationMessage;
import az.ideanest.notification.domain.NotificationChannel;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A channel with no transport behind it: email (#86) and push (#87).
 *
 * <p><strong>The name is the point.</strong> There is no {@code SmtpChannelSender} and
 * no {@code ExpoChannelSender} in this change, because a class named after a transport
 * that writes a log line and returns is a component that reports success for messages
 * nobody received — and every reader of the code, and every row in
 * {@code notifications}, would then agree that the backer was told their payment
 * failed. One class, named for what it actually is, registered for both channels, is
 * the version of that which cannot be misread.
 *
 * <h2>It returns rather than throws, and that is a choice with a real cost</h2>
 *
 * <p>Two wrong answers were available and neither is good:
 *
 * <ul>
 *   <li><strong>Throw.</strong> Honest about the row — nothing claims to have been sent
 *       — and it turns every email and push notification on the platform into eight
 *       failed attempts and then a dead letter. That is a queue that never drains, an
 *       {@code ERROR} per notification that should page somebody and would instead
 *       train them to ignore the page, and a {@code notifications_dead_idx} full of a
 *       missing feature rather than of incidents.
 *   <li><strong>Return.</strong> The row says {@code SENT} for a message that went
 *       nowhere, which is a lie in the database — bounded by the fact that no
 *       deployment has a transport at all, so the column means "handed to the channel"
 *       and the channel is a log file.
 * </ul>
 *
 * <p>The second is chosen, and it is the same choice {@code LoggingLaunchReminderNotifier}
 * already made for §4.10's launch reminder — "it fails visibly in the log, once per
 * follower, rather than quietly re-reading the same rows every minute forever". Making
 * it once, in one place, is better than two modules disagreeing about it.
 *
 * <p><strong>The line is {@code WARN}, deliberately.</strong> Not debug and not info: a
 * deployed environment that thinks it is telling backers about failed payments and is
 * not should say so at a level somebody's alerting can see, once per message, for as
 * long as this class is the implementation.
 *
 * <p>Nothing about the message body is logged — no recipient, no params. §17.4, and the
 * fact that {@link NotificationMessage} masks itself.
 */
public class UndeliverableChannelSender implements ChannelSender {

    private static final Logger log = LoggerFactory.getLogger(UndeliverableChannelSender.class);

    private final NotificationChannel channel;

    /** The issue that will replace this instance: #86 for email, #87 for push. */
    private final String issue;

    public UndeliverableChannelSender(NotificationChannel channel, String issue) {
        this.channel = Objects.requireNonNull(channel, "A sender is for some channel");
        this.issue = Objects.requireNonNull(issue, "A missing transport has an issue behind it");
    }

    @Override
    public NotificationChannel channel() {
        return channel;
    }

    @Override
    public void send(NotificationMessage message) {
        log.warn("{} was not sent: there is no {} transport configured ({}).", message, channel, issue);
    }
}
