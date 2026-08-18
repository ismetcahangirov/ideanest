package az.ideanest.notification.infrastructure;

import az.ideanest.notification.application.ChannelSender;
import az.ideanest.notification.domain.NotificationChannel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The two channels with nothing behind them.
 *
 * <p>Beans rather than two {@code @Component} classes, because there is one class and
 * two registrations of it — see {@link UndeliverableChannelSender} for why there is one
 * class. A pair of subclasses named {@code EmailChannelSender} and
 * {@code PushChannelSender} would put back exactly the misreading that class exists to
 * prevent.
 *
 * <p><strong>Replacing one of these is the whole of the wiring for #86 and #87.</strong>
 * A real sender is a {@code @Component} implementing {@link ChannelSender} and
 * returning the same {@link NotificationChannel}; the bean here is then deleted, and
 * nothing else in the module changes — {@code NotificationDispatch} finds senders by
 * channel and knows nothing about any of them.
 */
@Configuration(proxyBeanMethods = false)
public class ChannelSenderConfiguration {

    /** Transactional email — #86. */
    @Bean
    ChannelSender emailChannelSender() {
        return new UndeliverableChannelSender(NotificationChannel.EMAIL, "#86");
    }

    /** Push, over Expo and the platform services — #87, §14.4. */
    @Bean
    ChannelSender pushChannelSender() {
        return new UndeliverableChannelSender(NotificationChannel.PUSH, "#87");
    }
}
