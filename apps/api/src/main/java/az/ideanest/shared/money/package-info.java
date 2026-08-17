/**
 * Money: one type, one rounding rule, one wire format.
 *
 * <p>{@link az.ideanest.shared.money.Money} is the amount, with the arithmetic that
 * is safe to perform on one. {@link az.ideanest.shared.money.MoneyRounding} is the
 * single statement of the scale and the rounding mode — HALF_EVEN, at the currency's
 * minor unit — and every other class here defers to it rather than restating it.
 * {@link az.ideanest.shared.money.MoneySerializer} and
 * {@link az.ideanest.shared.money.MoneyDeserializer} are §10.3's "a string, never a
 * number", attached to the type so no call site can opt out;
 * {@link az.ideanest.shared.money.MoneyAmountConverter} holds a {@code numeric(14,2)}
 * column to the same rules.
 *
 * <p>In {@code shared} because goals, reward prices, pledges, fees, and payouts are
 * all money: a second definition anywhere is how one endpoint comes to answer
 * {@code "5000"} and another {@code "5000.00"} for the same value, and how one fee
 * calculation comes to round differently from the next.
 */
package az.ideanest.shared.money;
