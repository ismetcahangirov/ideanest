package az.ideanest.fx.application;

import az.ideanest.shared.money.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * "₼50.00 ≈ $29.41" — issue #327.
 *
 * <h2>Why the two amounts travel together and why the type is not `Money`</h2>
 *
 * A {@code Money} on its own is a claim about what something costs. This is a claim about
 * what something <em>roughly</em> costs, and the two must not be substitutable: a caller
 * that received a bare {@code Money} in the reader's currency could add it to a total, put
 * it on a receipt, or send it to a provider, and every one of those would be charging
 * somebody an approximation.
 *
 * <p>So the exact amount is carried beside the approximate one and is the field with the
 * plain name. {@link #approximate} says in its own name what it is, at every call site, for
 * ever.
 *
 * <h2>The rate is on the record</h2>
 *
 * §21.2: "the rate used is stored on the pledge, for audit". This is what a pledge stores,
 * and it is also what a screen needs in order to say <em>as of when</em> — an approximation
 * with no date beside it is a number a reader assumes is current, and the source publishes
 * on working days.
 *
 * @param exact what will actually be charged, in the campaign's currency
 * @param approximate the same amount in the reader's currency, rounded once to that
 *     currency's own minor unit. Never charged, never summed, never sent anywhere
 * @param rate units of {@code exact}'s currency per ONE unit of {@code approximate}'s
 * @param publishedFor the day the source says the rate is in force from
 */
public record Approximation(Money exact, Money approximate, BigDecimal rate, LocalDate publishedFor) {

    public Approximation {
        Objects.requireNonNull(exact, "An approximation approximates something");
        Objects.requireNonNull(approximate, "An approximation is of an amount");
        Objects.requireNonNull(rate, "An approximation used a rate");
        Objects.requireNonNull(publishedFor, "A rate is in force from a day");

        if (exact.currency().equals(approximate.currency())) {
            // Not defensive. A conversion into the currency the amount is already in is
            // the one case where a reader would see "₼50 ≈ ₼50", which reads as a
            // conversion that went wrong rather than as one that was not needed.
            // ExchangeRates answers that case with an empty Optional instead.
            throw new IllegalArgumentException(
                    "An amount is not an approximation of itself: " + exact.currency());
        }
    }
}
