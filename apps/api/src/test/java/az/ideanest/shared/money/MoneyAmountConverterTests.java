package az.ideanest.shared.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The guard on a money column, in both directions.
 *
 * <p>{@link MoneyAmountConverter} is what stops an amount reaching a
 * {@code numeric(14,2)} column with a place the column cannot hold — PostgreSQL
 * would round it, silently, and nothing downstream would know a charge had changed.
 * A unit test rather than an integration test because there is nothing about
 * PostgreSQL in the behaviour: the conversion is the whole of it, and it is the
 * conversion that has to refuse.
 */
class MoneyAmountConverterTests {

    private final MoneyAmountConverter converter = new MoneyAmountConverter();

    @Test
    @DisplayName("an amount is written at the scale of the column")
    void writingPadsToTheColumnScale() {
        assertThat(converter.convertToDatabaseColumn(new BigDecimal("5000")))
                .isEqualTo(new BigDecimal("5000.00"));
        assertThat(converter.convertToDatabaseColumn(new BigDecimal("5000.5")))
                .isEqualTo(new BigDecimal("5000.50"));
    }

    @Test
    @DisplayName("an amount the column would round is refused instead")
    void writingRefusesWhatTheColumnCannotHold() {
        assertThatThrownBy(() -> converter.convertToDatabaseColumn(new BigDecimal("5000.555")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most 2 decimal places");
    }

    @Test
    @DisplayName("a stored amount is read back at the same scale, whatever the driver returns")
    void readingNormalisesTheScale() {
        // PostgreSQL returns numeric(14,2) at scale 2, but a generated column, a
        // SUM, or a COALESCE can come back at another scale, and an amount whose
        // scale depends on the query is an amount whose equals() does.
        assertThat(converter.convertToEntityAttribute(new BigDecimal("5000")))
                .isEqualTo(new BigDecimal("5000.00"));
        assertThat(converter.convertToEntityAttribute(new BigDecimal("5000.00")))
                .isEqualTo(new BigDecimal("5000.00"));
    }

    @Test
    @DisplayName("a null column stays null")
    void nullIsNotZero() {
        // goal_amount is null while a campaign is a draft. Converting that to zero
        // would make an unset goal look like a goal of nothing, and §5.3's
        // checklist reads exactly this column to decide whether one was set.
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }
}
