package az.ideanest.shared.money;

import java.math.BigDecimal;
import java.util.Collection;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;

/**
 * Reads {@code {"amount": "599.00", "currency": "AZN"}}, and refuses everything else.
 *
 * <p><strong>A JSON number is refused rather than coerced.</strong> This is the half
 * of §10.3 that a lenient reader would quietly undo: a client holding an amount in a
 * double sends {@code 1234.5599999999999} or {@code 1234.56} depending on its
 * platform, and a deserialiser that accepts numbers takes whichever arrives. Refusing
 * is the only answer that tells the client its own representation is the problem,
 * while it can still fix it — rather than after a card has been charged.
 *
 * <p><strong>An unrecognised property is refused too.</strong> On a payment request,
 * ignoring a field is how a client comes to believe it set a value it did not set.
 *
 * <p>Every refusal goes through {@link DeserializationContext#reportInputMismatch},
 * so a bad body is a Jackson databind failure and reaches the client as a 400 rather
 * than escaping as an {@link IllegalArgumentException} and being reported as a server
 * error. That includes the value object's own rules — a third decimal place, a
 * currency that is not a code — which are caught and re-reported for exactly that
 * reason.
 */
class MoneyDeserializer extends ValueDeserializer<Money> {

    private static final String AMOUNT = "amount";
    private static final String CURRENCY = "currency";

    @Override
    public Money deserialize(JsonParser parser, DeserializationContext context) {
        JsonNode node = context.readTree(parser);
        if (!node.isObject()) {
            return context.reportInputMismatch(
                    Money.class,
                    "Money is an object with an \"%s\" and a \"%s\", per §10.3, not %s",
                    AMOUNT,
                    CURRENCY,
                    node.getClass().getSimpleName());
        }

        Collection<String> properties = node.propertyNames();
        for (String property : properties) {
            if (!AMOUNT.equals(property) && !CURRENCY.equals(property)) {
                return context.reportInputMismatch(
                        Money.class,
                        "Money has an \"%s\" and a \"%s\" and nothing else, so \"%s\" would be ignored",
                        AMOUNT,
                        CURRENCY,
                        property);
            }
        }

        String amount = string(node, AMOUNT, context);
        String currency = string(node, CURRENCY, context);

        try {
            return Money.of(new BigDecimal(amount), currency);
        } catch (NumberFormatException notANumber) {
            return context.reportInputMismatch(Money.class, "An \"%s\" of \"%s\" is not a decimal", AMOUNT, amount);
        } catch (IllegalArgumentException refused) {
            // The value object's own rules, reported as a bad request rather than
            // allowed to escape as a server error: the client sent something it can
            // correct.
            return context.reportInputMismatch(Money.class, "%s", refused.getMessage());
        }
    }

    /** One of the two properties, which has to be present and has to be a string. */
    private static String string(JsonNode node, String property, DeserializationContext context) {
        JsonNode value = node.get(property);
        if (value == null || value.isNull()) {
            return context.reportInputMismatch(Money.class, "Money requires a value for \"%s\"", property);
        }
        if (!value.isString()) {
            return context.reportInputMismatch(
                    Money.class,
                    "An \"%s\" crosses the API as a string, never as a JSON number: §10.3, because a JSON number"
                            + " is a double in every mainstream parser",
                    property);
        }
        return value.stringValue();
    }
}
