package az.ideanest.reward.application;

import java.util.List;
import java.util.UUID;

/**
 * One region as the rate editor reads it.
 *
 * <p>The identifier is here and not on {@link ZoneDefinition} because it is what a
 * tier's rates name: a client renders the zone list from this, and sends a zone rate
 * keyed on {@link #id()}.
 */
public record ShippingZoneDetail(UUID id, String name, List<String> countryCodes) {

    public ShippingZoneDetail {
        countryCodes = List.copyOf(countryCodes);
    }
}
