package az.ideanest.notification.infrastructure;

/**
 * Nothing, now — and the file stays for what it records.
 *
 * <p>It held one bean per channel with no transport behind it. Email lost its when #86
 * landed and push lost its when #87 did, and in both cases <strong>deleting the bean was
 * genuinely the whole of the wiring</strong>: a {@code @Component} implementing
 * {@link az.ideanest.notification.application.ChannelSender} and returning its channel is
 * found by {@code NotificationDispatch}, which indexes senders by channel and knows
 * nothing about any of them.
 *
 * <p>{@link UndeliverableChannelSender} is still here and is still worth reading. It is
 * the argument for why a class named after a transport that logs and returns is worse
 * than one named for what it actually is, and it is what a fourth channel should be
 * registered as on the day {@code NotificationChannel} grows one — a channel with a name
 * and no transport is a channel that reports success for messages nobody received.
 *
 * <p>The class is kept rather than deleted because that argument has nowhere else to live
 * and because the next channel needs the three lines back. Spring instantiates it and it
 * does nothing, which costs one object at start-up.
 */
@org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
public class ChannelSenderConfiguration {}
