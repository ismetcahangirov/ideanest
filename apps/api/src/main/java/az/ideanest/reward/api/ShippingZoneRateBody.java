package az.ideanest.reward.api;

import az.ideanest.reward.application.ZoneRate;
import az.ideanest.reward.domain.ShippingZoneRule;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * One region's shipping rate for one tier, in a request and in a response.
 *
 * <p>All three amounts cross the wire as strings, for {@link ShippingRuleBody}'s
 * reason: §10.3, and a JSON number is an IEEE 754 double in every mainstream parser.
 *
 * <p>The zone is named by identifier rather than by name, because a region can be
 * renamed and a rate table that referred to "EU" would then price nothing.
 *
 * @param additionalItemAmount what each unit after the first costs. Omitted means
 *     free
 * @param perKilogramAmount §4.8's PM-12: what each kilogram costs, added to
 *     {@code amount} rather than replacing it. Omitted means this tier is not priced
 *     by weight
 */
public record ShippingZoneRateBody(
        UUID zoneId,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal additionalItemAmount,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal perKilogramAmount) {

    public static ShippingZoneRateBody of(ShippingZoneRule rule) {
        return new ShippingZoneRateBody(
                rule.getZoneId(),
                rule.getAmount(),
                rule.getAdditionalItemAmount(),
                rule.getPerKilogramAmount());
    }

    public ZoneRate toRate() {
        return new ZoneRate(zoneId, amount, additionalItemAmount, perKilogramAmount);
    }
}
