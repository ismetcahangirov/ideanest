package az.ideanest.reward.application;

import java.util.List;

/**
 * One region a creator is asking for: what it is called and what it covers.
 *
 * <p>No identifier. A zone is matched to an existing one by its folded name — see
 * {@code ShippingZoneService} for why, and for what that makes a rename — so a
 * client that sent an identifier would be sending something the service is
 * deliberately not going to read.
 */
public record ZoneDefinition(String name, List<String> countryCodes) {
}
