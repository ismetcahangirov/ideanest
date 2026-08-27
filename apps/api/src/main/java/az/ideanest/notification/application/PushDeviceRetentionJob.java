package az.ideanest.notification.application;

import az.ideanest.notification.NotificationProperties;
import az.ideanest.shared.jobs.ScheduledJob;
import org.springframework.stereotype.Component;

/**
 * Forgets push registrations nobody has refreshed — §17.4, and the half of issue #87 that
 * is about not keeping things.
 *
 * <h2>Why a sweep is the only way this table shrinks</h2>
 *
 * <p>A device stops being reachable in three ways and only two of them tell us. Signing
 * out deletes the row. An uninstall is discovered the first time a send is refused with
 * {@code DeviceNotRegistered}, and {@code PushChannelSender} drops it then. The third way
 * — a phone that is simply never opened again — produces no signal at all, ever, and
 * nothing but time distinguishes it from a phone somebody is about to pick up.
 *
 * <p>So the rule is time: the application re-registers on every cold start, so a
 * registration nobody has refreshed in
 * {@code ideanest.notification.push.forget-after} is an address nobody has confirmed for
 * that long. §17.4's minimisation says to stop keeping it.
 *
 * <p><strong>Nothing is lost that cannot come back.</strong> The next time the
 * application opens it registers again, with whatever token is current then — which is
 * more likely to be deliverable than the one that went stale.
 *
 * <p>Throwing is how a failed pass is recorded: {@code JobRunner} counts the attempt,
 * releases the lease and backs off. Catching a failure here to keep the log tidy would
 * have the sweep recorded as having run.
 */
@Component
public class PushDeviceRetentionJob implements ScheduledJob {

    private final PushDevices devices;
    private final NotificationProperties properties;

    public PushDeviceRetentionJob(PushDevices devices, NotificationProperties properties) {
        this.devices = devices;
        this.properties = properties;
    }

    @Override
    public String name() {
        return "push-device-retention";
    }

    @Override
    public String schedule() {
        return properties.push().forgetSchedule();
    }

    @Override
    public void run() {
        devices.forgetUnusedSince(properties.push().forgetAfter());
    }
}
