package az.ideanest.compliance;

import static org.assertj.core.api.Assertions.assertThat;

import az.ideanest.compliance.domain.DestinationState;
import az.ideanest.compliance.domain.DestinationVerificationMethod;
import az.ideanest.payment.application.PaymentProviders;
import az.ideanest.payment.domain.ProviderName;
import az.ideanest.shared.compliance.RejectionReason;
import az.ideanest.support.AbstractIntegrationTest;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V72's closed sets and the enums they repeat, checked against each other — part of #432.
 *
 * <p><strong>Three vocabularies are written down twice</strong>, and each of the pairs is one
 * somebody will one day extend on one side only. The provider is the dangerous one: adding a
 * value to {@code ProviderName} without touching the constraint fails at the first insert
 * against the new provider, in production, on a creator's settings screen. This turns that into
 * a test failure.
 *
 * <p>The duplication is deliberate rather than an accident to be refactored away.
 * {@code ProviderName} is {@code payment.domain} and the compliance module may not name it —
 * {@code ModuleBoundaryTests} — so the column is a string with a CHECK on it, and this suite is
 * what makes the string as safe as the enum would have been.
 */
@DisplayName("V72's vocabularies")
class PayoutDestinationSchemaTests extends AbstractIntegrationTest {

    /** Pulls the quoted values out of a {@code CHECK (col IN ('A', 'B'))} clause. */
    private static final Pattern QUOTED = Pattern.compile("'([A-Z_]+)'");

    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("the provider constraint lists exactly §9.3's providers")
    void theProviderConstraintMatchesTheEnum() {
        Set<String> constrained = valuesIn("payout_destinations_provider_known");

        Set<String> declared =
                Arrays.stream(ProviderName.values()).map(Enum::name).collect(Collectors.toSet());

        assertThat(constrained)
                .withFailMessage(
                        "V72's provider CHECK and ProviderName have drifted. Constraint: %s. Enum: %s. "
                                + "Adding a provider needs a migration that widens the constraint.",
                        constrained, declared)
                .isEqualTo(declared);
    }

    @Test
    @DisplayName("PaymentProviders publishes the same vocabulary the constraint enforces")
    void theCrossModuleQuestionAgreesWithBoth() {
        // The compliance module never sees ProviderName; what it sees is this method. If the
        // two ever disagreed, a creator could file a destination the database then refused.
        for (ProviderName provider : ProviderName.values()) {
            assertThat(PaymentProviders.canonicalNameOf(provider.name().toLowerCase(Locale.ROOT)))
                    .contains(provider.name());
        }
        assertThat(PaymentProviders.canonicalNameOf("stripe")).isEmpty();
        assertThat(PaymentProviders.canonicalNameOf(null)).isEmpty();
    }

    @Test
    @DisplayName("the state constraint lists exactly the states a row can be in")
    void theStateConstraintMatchesTheEnum() {
        assertThat(valuesIn("payout_destinations_state_known"))
                .isEqualTo(Arrays.stream(DestinationState.values())
                        .map(Enum::name)
                        .collect(Collectors.toSet()));
    }

    @Test
    @DisplayName("the verification-method constraint lists exactly the three mechanisms")
    void theMethodConstraintMatchesTheEnum() {
        // Including the two that nothing writes yet. They are values because the column has to
        // describe what a stored row means, and a row written by the micro-transfer once it
        // exists must not read the same as one a person attested to by eye.
        assertThat(valuesIn("payout_destinations_method_known"))
                .isEqualTo(Arrays.stream(DestinationVerificationMethod.values())
                        .map(Enum::name)
                        .collect(Collectors.toSet()));
    }

    @Test
    @DisplayName("the rejection-reason constraint lists exactly V58's closed set")
    void theRejectionConstraintMatchesTheEnum() {
        assertThat(valuesIn("payout_destinations_rejection_known"))
                .isEqualTo(Arrays.stream(RejectionReason.values())
                        .map(Enum::name)
                        .collect(Collectors.toSet()));
    }

    /** The quoted values inside one named CHECK constraint, as PostgreSQL renders it back. */
    private Set<String> valuesIn(String constraintName) {
        String definition = new JdbcTemplate(dataSource)
                .queryForObject(
                        """
                        SELECT pg_get_constraintdef(oid)
                          FROM pg_constraint
                         WHERE conname = ?
                        """,
                        String.class,
                        constraintName);

        assertThat(definition)
                .withFailMessage("No constraint called %s. V72 renamed or dropped it.", constraintName)
                .isNotNull();

        Matcher matcher = QUOTED.matcher(definition);
        List<String> found = new java.util.ArrayList<>();
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        return Set.copyOf(found);
    }
}
