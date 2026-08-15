package az.ideanest.reward.application;

import java.math.BigDecimal;

/**
 * What shipping a tier costs to one destination.
 *
 * <p>{@link BigDecimal} rather than a {@code double}, here as everywhere: this
 * number is added to a pledge total and charged to a card.
 *
 * <p>No currency, because shipping is charged in the campaign's currency and the
 * tier already carries it. See {@code ShippingRule}.
 *
 * @param additionalItemAmount what each unit after the first costs. Zero is
 *     legitimate and is what an omitted value means — a flat rate however many are
 *     ordered is an offer creators make deliberately
 */
public record ShippingRate(String countryCode, BigDecimal amount, BigDecimal additionalItemAmount) {
}
