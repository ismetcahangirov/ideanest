package az.ideanest.reward.api;

import az.ideanest.reward.application.ShippingZoneDetail;
import java.util.List;

/**
 * A campaign's shipping regions.
 *
 * <p>An object with one field rather than a bare array, as every list response on
 * this platform is: a top-level array cannot gain a field, and the first thing this
 * one will want is a count or a warning about destinations nothing prices.
 */
public record ShippingZoneListResponse(List<ShippingZoneBody> zones) {

    public static ShippingZoneListResponse of(List<ShippingZoneDetail> zones) {
        return new ShippingZoneListResponse(zones.stream().map(ShippingZoneBody::of).toList());
    }
}
