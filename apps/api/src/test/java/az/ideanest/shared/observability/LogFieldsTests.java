package az.ideanest.shared.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The helper that makes the safe thing the easy thing. */
class LogFieldsTests {

    @Test
    @DisplayName("renders the shapes that cannot carry personal data")
    void rendersSafeShapes() {
        UUID pledge = UUID.fromString("0192f0c1-8f3a-7c2b-9d4e-1a2b3c4d5e6f");

        String rendered = LogFields.create()
                .id("pledgeId", pledge)
                .amount("contribution", new BigDecimal("50.00"))
                .count("addons", 2)
                .flag("anonymous", true)
                .at("confirmedAt", Instant.parse("2026-08-17T10:22:31Z"))
                .country("shippingCountry", "AZ")
                .toString();

        assertThat(rendered)
                .isEqualTo("pledgeId=0192f0c1-8f3a-7c2b-9d4e-1a2b3c4d5e6f contribution=50.00 addons=2 "
                        + "anonymous=true confirmedAt=2026-08-17T10:22:31Z shippingCountry=AZ");
    }

    @Test
    @DisplayName("a null value says so rather than being dropped")
    void rendersAbsence() {
        assertThat(LogFields.create().id("rewardTierId", null).toString()).isEqualTo("rewardTierId=none");
    }

    @Test
    @DisplayName("a country code that is not one is refused rather than logged")
    void refusesAnythingOutsideItsShape() {
        LogFields fields = LogFields.create();

        assertThatThrownBy(() -> fields.country("shippingCountry", "Baku, 28 May kucesi 14"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a field name the pipeline would mask anyway is refused at the call site")
    void refusesASensitiveFieldName() {
        LogFields fields = LogFields.create();

        assertThatThrownBy(() -> fields.count("password", 12)).isInstanceOf(IllegalArgumentException.class);
    }
}
