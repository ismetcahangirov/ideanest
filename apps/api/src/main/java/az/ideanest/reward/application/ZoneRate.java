package az.ideanest.reward.application;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * What one tier charges to ship to one zone — §4.8's PM-12 and PM-13 (#77).
 *
 * <p>The counterpart of {@link ShippingRate} for a group of destinations rather than
 * a named one. It names the zone by identifier and not by name, because a zone can
 * be renamed and a rate table that referred to "EU" would then price nothing.
 *
 * <p>No currency, for {@link ShippingRate}'s reason: shipping is charged in the
 * campaign's currency, which the tier already carries.
 *
 * @param additionalItemAmount what each unit after the first costs. Null means free —
 *     a flat rate however many are ordered is an offer creators make deliberately
 * @param perKilogramAmount what each kilogram costs, added to {@code amount} rather
 *     than replacing it. Null means this tier is not priced by weight
 */
public record ZoneRate(
        UUID zoneId, BigDecimal amount, BigDecimal additionalItemAmount, BigDecimal perKilogramAmount) {
}
