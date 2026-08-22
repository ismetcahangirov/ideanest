package az.ideanest.pledgemanager.api;

import az.ideanest.pledgemanager.application.StoredAddress;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.UUID;

/**
 * A pledge's address as its backer sees it — §4.8's PM-07 and PM-08.
 *
 * @param locked whether the creator has frozen it. A boolean as well as the instant,
 *     because a client renders a disabled form from the first and a sentence from the
 *     second, and deriving one from the other in three components is three chances to
 *     get the null check wrong
 * @param updatedAt when it last changed, which is what tells a backer whether the
 *     answer they are looking at is the one they gave
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ShippingAddressResponse(
        UUID pledgeId, PostalAddressBody address, boolean locked, Instant lockedAt, Instant updatedAt) {

    public static ShippingAddressResponse of(StoredAddress stored) {
        return new ShippingAddressResponse(
                stored.pledgeId(),
                PostalAddressBody.of(stored.address()),
                stored.isLocked(),
                stored.lockedAt(),
                stored.updatedAt());
    }
}
