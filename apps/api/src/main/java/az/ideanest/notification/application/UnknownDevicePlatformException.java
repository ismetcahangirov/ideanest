package az.ideanest.notification.application;

/**
 * A registration naming a platform this build does not know — issue #87.
 *
 * <p>Refused rather than defaulted. {@code push_devices.platform} exists to answer one
 * question — "is this failing on one platform only?" — and quietly recording an unknown
 * value as iOS would turn the answer into a guess, in the one column that was added for
 * the purpose.
 *
 * <p>A client sending one is newer than this service, which is a deployment ordering
 * problem rather than an attack, and a 400 naming the field is what makes it visible.
 */
public class UnknownDevicePlatformException extends RuntimeException {

    public UnknownDevicePlatformException() {
        super("That is not a platform this service records");
    }
}
