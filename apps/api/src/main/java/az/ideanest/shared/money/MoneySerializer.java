package az.ideanest.shared.money;

import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;

/**
 * Writes {@code {"amount": "599.00", "currency": "AZN"}}, per §10.3.
 *
 * <p><strong>The amount is a string, always.</strong> A JSON number is an IEEE 754
 * double in every mainstream parser, so serialising {@code 599.00} as a number
 * invites a client to read it into a value that cannot represent it exactly — and
 * nothing anywhere reports an error when it does. The discrepancy appears later, in
 * a total that does not add up.
 *
 * <p>An explicit serialiser rather than {@code @JsonFormat(shape = STRING)} on the
 * amount, because the annotation is a hint about one field and this is the wire
 * format of the type: with the serialiser, "money is an object with a string amount
 * and a currency" is one implementation that every response shares, and there is no
 * arrangement of annotations on a call site that can produce a different shape.
 *
 * <p>{@code toPlainString} rather than {@code toString}: {@link java.math.BigDecimal}
 * switches to scientific notation for some values, and {@code "5E+3"} is not
 * something a client should have to parse for a goal of 5000 manat.
 */
class MoneySerializer extends ValueSerializer<Money> {

    @Override
    public void serialize(Money money, JsonGenerator generator, SerializationContext context) {
        generator.writeStartObject();
        generator.writeStringProperty("amount", money.amount().toPlainString());
        generator.writeStringProperty("currency", money.currency());
        generator.writeEndObject();
    }
}
