package az.ideanest.shared.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Money on the wire, in both directions, on a mapper nobody configured.
 *
 * <p>§10.3: {@code {"amount": "599.00", "currency": "AZN"}} — <strong>a string,
 * never a number</strong>, because a JSON number is an IEEE 754 double in every
 * mainstream parser. The serialiser and the deserialiser are attached to the type
 * itself rather than registered with one {@code ObjectMapper}, and this suite uses
 * a plain {@code new ObjectMapper()} on purpose: that is what proves the guarantee
 * does not depend on anybody remembering to configure a mapper. The idempotency
 * fingerprint in {@code IdempotentRequests} serialises request objects through a
 * mapper of its own, and a guarantee that held only for the Spring one would not
 * hold there.
 */
class MoneyJsonTests {

    private final ObjectMapper json = new ObjectMapper();

    /** A money field inside something else, which is how every response carries one. */
    private record Quote(Money total) {
    }

    @Test
    @DisplayName("the amount is written as a string, and the currency beside it")
    void theAmountIsAStringOnTheWire() {
        assertThat(json.writeValueAsString(Money.of(new BigDecimal("5000.00"), "AZN")))
                .isEqualTo("{\"amount\":\"5000.00\",\"currency\":\"AZN\"}");

        // Nested, and with the scale padded to the currency's minor unit, so a
        // client comparing what it sent against what it got back sees the same
        // string.
        assertThat(json.writeValueAsString(new Quote(Money.of(new BigDecimal("30"), "AZN"))))
                .isEqualTo("{\"total\":{\"amount\":\"30.00\",\"currency\":\"AZN\"}}");
    }

    @Test
    @DisplayName("an amount sent as a string is read back exactly")
    void anAmountRoundTrips() {
        Money money = json.readValue("{\"amount\":\"1234.56\",\"currency\":\"AZN\"}", Money.class);

        assertThat(money).isEqualTo(Money.of(new BigDecimal("1234.56"), "AZN"));
        assertThat(json.readValue(json.writeValueAsString(money), Money.class)).isEqualTo(money);
    }

    @Test
    @DisplayName("an amount sent as a JSON number is refused, not coerced")
    void aNumericAmountIsRefused() {
        // The failure this prevents: a client holds 1234.56 in a double, sends
        // 1234.5599999999999 or 1234.56 depending on its platform, and the
        // platform accepts whichever arrives. Refusing is the only answer that
        // tells the client its own representation is the problem.
        assertThatThrownBy(() -> json.readValue("{\"amount\":1234.56,\"currency\":\"AZN\"}", Money.class))
                .isInstanceOf(JacksonException.class)
                .hasMessageContaining("string");

        // Including a whole number, which is the tempting one to allow.
        assertThatThrownBy(() -> json.readValue("{\"amount\":5000,\"currency\":\"AZN\"}", Money.class))
                .isInstanceOf(JacksonException.class);
    }

    @Test
    @DisplayName("an incomplete or malformed amount is refused")
    void anIncompleteAmountIsRefused() {
        assertThatThrownBy(() -> json.readValue("{\"currency\":\"AZN\"}", Money.class))
                .isInstanceOf(JacksonException.class)
                .hasMessageContaining("amount");
        assertThatThrownBy(() -> json.readValue("{\"amount\":\"10.00\"}", Money.class))
                .isInstanceOf(JacksonException.class)
                .hasMessageContaining("currency");
        assertThatThrownBy(() -> json.readValue("{\"amount\":null,\"currency\":\"AZN\"}", Money.class))
                .isInstanceOf(JacksonException.class);
        assertThatThrownBy(() -> json.readValue("{\"amount\":\"ten\",\"currency\":\"AZN\"}", Money.class))
                .isInstanceOf(JacksonException.class);

        // The value-object rules apply to a document exactly as they apply to a
        // constructor: a third decimal place and a currency that is not a code
        // are refused rather than repaired, and the refusal is a bad request
        // rather than a server error.
        assertThatThrownBy(() -> json.readValue("{\"amount\":\"10.005\",\"currency\":\"AZN\"}", Money.class))
                .isInstanceOf(JacksonException.class);
        assertThatThrownBy(() -> json.readValue("{\"amount\":\"10.00\",\"currency\":\"manat\"}", Money.class))
                .isInstanceOf(JacksonException.class);
    }

    @Test
    @DisplayName("money is an object, and only the two fields it declares")
    void theShapeIsFixed() {
        // A bare "10.00 AZN" is somebody's convention, not this platform's, and
        // guessing at it would make the wire format two formats.
        assertThatThrownBy(() -> json.readValue("\"10.00 AZN\"", Money.class)).isInstanceOf(JacksonException.class);

        // An extra field is a client sending something the platform will ignore.
        // On a payment request that is how a client believes it set a value it
        // did not set.
        assertThatThrownBy(() ->
                        json.readValue("{\"amount\":\"10.00\",\"currency\":\"AZN\",\"vat\":\"1.80\"}", Money.class))
                .isInstanceOf(JacksonException.class)
                .hasMessageContaining("vat");
    }
}
