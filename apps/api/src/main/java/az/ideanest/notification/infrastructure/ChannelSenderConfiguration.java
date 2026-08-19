package az.ideanest.notification.infrastructure;

import az.ideanest.notification.application.ChannelSender;
import az.ideanest.notification.domain.NotificationChannel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The channel with nothing behind it.
 *
 * <p>One, now: #86 landed, and this class is the record of what that took. The email
 * bean here was a {@link UndeliverableChannelSender} and is gone —
 * {@code EmailChannelSender} is a {@code @Component} implementing {@link ChannelSender}
 * and returning {@link NotificationChannel#EMAIL}, and <strong>deleting the bean was
 * genuinely the whole of the wiring</strong>. Nothing else in the module changed:
 * {@code NotificationDispatch} indexes senders by channel and knows nothing about any of
 * them.
 *
 * <p>A bean rather than a {@code @Component} class, still, because
 * {@link UndeliverableChannelSender} is one class registered for a channel rather than a
 * class per channel — see it for why. A subclass named {@code PushChannelSender} would
 * put back exactly the misreading it exists to prevent.
 *
 * <p><strong>#87 is the same three lines in reverse.</strong>
 */
@Configuration(proxyBeanMethods = false)
public class ChannelSenderConfiguration {

    /** Push, over Expo and the platform services — #87, §14.4. */
    @Bean
    ChannelSender pushChannelSender() {
        return new UndeliverableChannelSender(NotificationChannel.PUSH, "#87");
    }
}
