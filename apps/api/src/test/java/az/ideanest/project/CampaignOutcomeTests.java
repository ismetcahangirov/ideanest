package az.ideanest.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import az.ideanest.project.domain.CampaignOutcome;
import az.ideanest.project.domain.ProjectState;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * §5.1's comparison, checked without a database.
 *
 * <p>A plain unit test on purpose, for {@code ProjectStateMachineTests}' reason: the rule
 * that decides whether ten thousand people are charged should be assertable by something
 * that starts nothing. {@code CampaignFinalisationTests} exercises the same rule through
 * the sweep, the lock and the transition — this is the arithmetic on its own, where a
 * failure names the arithmetic.
 */
class CampaignOutcomeTests {

    @ParameterizedTest(name = "{0} raised against a goal of {1} is {2}")
    @CsvSource({
        // The boundary, from both sides and on it. `>` instead of `>=` fails only the
        // middle row, and only for the creator who hit the number exactly.
        "9999.99, 10000.00, UNSUCCESSFUL",
        "10000.00, 10000.00, SUCCESSFUL",
        "10000.01, 10000.00, SUCCESSFUL",
        // A campaign nobody backed is a real outcome, not a missing one.
        "0.00, 10000.00, UNSUCCESSFUL",
        // Comfortably over, which is the case everybody tests by hand.
        "42000.00, 10000.00, SUCCESSFUL",
        // The same amount written two ways. compareTo says equal; equals does not, and
        // this is the row that would fail if somebody reached for the wrong one.
        "1000.0, 1000.000, SUCCESSFUL",
        // A goal of zero is not something §5.3 permits, and if one ever reached LIVE the
        // answer has to be "succeeded" rather than an exception inside a sweep.
        "0.00, 0.00, SUCCESSFUL",
    })
    void theComparisonIsGreaterThanOrEqual(String pledged, String goal, CampaignOutcome expected) {
        assertThat(CampaignOutcome.of(new BigDecimal(pledged), new BigDecimal(goal)))
                .isEqualTo(expected);
    }

    @Test
    @DisplayName("each outcome names the state §6.1 gives it")
    void eachOutcomeNamesItsState() {
        assertThat(CampaignOutcome.SUCCESSFUL.state()).isEqualTo(ProjectState.SUCCESSFUL);
        assertThat(CampaignOutcome.UNSUCCESSFUL.state()).isEqualTo(ProjectState.UNSUCCESSFUL);
    }

    /**
     * A campaign with no goal is a bug in whatever put it live, and closing it quietly as
     * unsuccessful would hide that bug behind somebody's failed campaign.
     */
    @Test
    @DisplayName("a live campaign without a goal cannot be decided")
    void aMissingGoalIsRefusedRatherThanTreatedAsZero() {
        assertThatThrownBy(() -> CampaignOutcome.of(new BigDecimal("500.00"), null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> CampaignOutcome.of(null, new BigDecimal("500.00")))
                .isInstanceOf(NullPointerException.class);
    }
}
