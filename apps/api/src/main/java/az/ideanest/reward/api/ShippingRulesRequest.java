package az.ideanest.reward.api;

import az.ideanest.reward.application.ShippingRate;
import java.util.List;

/**
 * A tier's complete shipping rate table.
 *
 * <p>{@code PUT}, and the whole table: a country left out of the body is a country the
 * creator has removed. Merging instead would leave them shipping somewhere they believe
 * they no longer do, and that is discovered by a backer selecting it.
 *
 * <p>An empty list is therefore a legitimate request, and it clears the table.
 */
public record ShippingRulesRequest(List<ShippingRuleBody> rules) {

    public ShippingRulesRequest {
        rules = rules == null ? List.of() : rules;
    }

    /** A null line is refused by name rather than dereferenced into a 500. */
    public List<ShippingRate> toRates() {
        return rules.stream().map(rule -> rule == null ? null : rule.toRate()).toList();
    }
}
