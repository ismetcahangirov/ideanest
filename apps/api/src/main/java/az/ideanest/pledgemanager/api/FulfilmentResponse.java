package az.ideanest.pledgemanager.api;

import az.ideanest.pledgemanager.domain.Fulfilment;
import az.ideanest.pledgemanager.domain.FulfilmentStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.UUID;

/**
 * One parcel — §4.8's PM-20 to PM-22.
 *
 * <p>The same shape for both readers. A creator's list is many of these and a backer's
 * own row is one of them inside {@link BackerFulfilmentResponse}, because the fields
 * are the same fields and two records would be two places to add the next one.
 *
 * <p>It names the pledge and never the backer. The creator already knows whose pledge
 * it is — the backer report is where that join belongs and it is audited — and putting
 * a name here would make the fulfilment list a second export of personal data with no
 * audit row behind it.
 *
 * @param status one of PREPARING, SHIPPED, DELIVERED, RETURNED
 * @param shippedAt null until it has left; a fact about {@code status} rather than a
 *     second opinion about it, which V38 holds with a check constraint
 * @param updatedAt when the creator last said anything about this parcel. What a
 *     backer looks at to decide whether "preparing" is news or three months old
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record FulfilmentResponse(
        UUID pledgeId,
        FulfilmentStatus status,
        String carrier,
        String trackingNumber,
        String trackingUrl,
        Instant shippedAt,
        Instant deliveredAt,
        Instant updatedAt) {

    public static FulfilmentResponse of(Fulfilment fulfilment) {
        return new FulfilmentResponse(
                fulfilment.getPledgeId(),
                fulfilment.getStatus(),
                fulfilment.getCarrier(),
                fulfilment.getTrackingNumber(),
                fulfilment.getTrackingUrl(),
                fulfilment.getShippedAt(),
                fulfilment.getDeliveredAt(),
                fulfilment.getUpdatedAt());
    }
}
