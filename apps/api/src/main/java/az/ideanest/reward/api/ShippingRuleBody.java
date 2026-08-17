package az.ideanest.reward.api;

import az.ideanest.reward.application.ShippingRate;
import az.ideanest.reward.domain.ShippingRule;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;

/**
 * One destination's shipping rate, in a request and in a response.
 *
 * <p><strong>Both amounts cross the wire as strings.</strong> §10.3 and
 * {@code CLAUDE.md}: a JSON number is an IEEE 754 double in every mainstream parser,
 * and this number is added to a pledge total and charged to a card. {@link JsonFormat}
 * is what makes that true here; {@code shared.money.Money} has a serialiser of its own
 * (#133) that does it for the same reason, and refuses a JSON number outright.
 *
 * <p>Deliberately not a {@code Money}, unlike a reward's price. A rate table is
 * denominated in the campaign's currency for every row, so repeating the currency on
 * each of thirty destinations would be thirty opportunities for one of them to
 * disagree with the tier it belongs to — and the request shape in the epic's contract
 * is a bare amount for exactly that reason.
 *
 * @param additionalItemAmount what each unit after the first costs. Omitted means
 *     free: a flat rate however many are ordered is an offer creators make on purpose
 */
public record ShippingRuleBody(
        String countryCode,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal additionalItemAmount) {

    public static ShippingRuleBody of(ShippingRule rule) {
        return new ShippingRuleBody(rule.getCountryCode(), rule.getAmount(), rule.getAdditionalItemAmount());
    }

    public ShippingRate toRate() {
        return new ShippingRate(countryCode, amount, additionalItemAmount);
    }
}
