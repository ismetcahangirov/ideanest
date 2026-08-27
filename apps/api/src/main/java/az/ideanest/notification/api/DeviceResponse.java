package az.ideanest.notification.api;

import az.ideanest.notification.domain.PushDevice;
import java.time.Instant;
import java.util.UUID;

/**
 * A registration, as the phone that made it is told about it — issue #87.
 *
 * <h2>The token is not in it</h2>
 *
 * <p>The client already has the token; it is what it just sent. Echoing it would put an
 * address into every response body, every client log, and every proxy that records one,
 * in exchange for nothing the caller does not already know.
 *
 * @param id the registration, so a client can refer to it without holding the token
 * @param platform as recorded, which may differ in case from what was sent
 * @param registeredAt when this installation first registered — not when this call
 *     happened, so a client can see that re-registering did not create a new row
 * @param lastSeenAt when it last did, which this call has just moved
 */
public record DeviceResponse(UUID id, String platform, Instant registeredAt, Instant lastSeenAt) {

    public static DeviceResponse of(PushDevice device) {
        return new DeviceResponse(
                device.getId(),
                device.getPlatform().name(),
                device.getCreatedAt(),
                device.getLastSeenAt());
    }
}
